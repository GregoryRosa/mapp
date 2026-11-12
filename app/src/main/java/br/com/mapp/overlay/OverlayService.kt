package br.com.mapp.overlay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import br.com.mapp.MappApp
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneHit
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.shift.Odometer
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftTrip
import br.com.mapp.maps.LocationProvider
import br.com.mapp.maps.PinStore
import br.com.mapp.reader.ReaderStatus
import br.com.mapp.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano do copiloto: roda o [OfferPipeline] e desenha o [OfferCardView]
 * sobre os apps de corrida. Só lê e mostra — nunca toca na tela de outro app.
 */
class OverlayService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var overlayContext: Context? = null
    private var location: LocationProvider? = null
    private var voice: VoiceAnnouncer? = null
    private var card: OfferCardView? = null
    private var bubble: QuickBubbleView? = null
    private var profiles: List<FilterProfile> = emptyList()
    private var activeProfile: FilterProfile = FilterProfile()

    private var zones: List<Zone> = emptyList()
    private var shownKey: RawOffer? = null
    private var dismissedKey: RawOffer? = null
    private var announcedZones: Set<ZoneHit> = emptySet()

    // ---- Jornada ----
    private var costPerKm: Double = 0.0
    /** Última oferta avaliada (para "+1 Contar última" depois que o cartão some). */
    private var lastOffer: EvaluatedOffer? = null
    /** Tipo lido em algum quadro da oferta atual (o OCR às vezes não lê o "UberX"). */
    private var lastCategory: Pair<RawOffer, String>? = null
    private var countedKey: RawOffer? = null
    /** Oferta contada por último (para desfazer a marca de aceita no histórico). */
    private var countedOffer: EvaluatedOffer? = null
    /** Oferta já marcada como aceita no histórico (não regravar a cada quadro). */
    private var markedAcceptedKey: RawOffer? = null
    /** Quando a [lastOffer] esteve no cartão pela última vez (para o aceite automático). */
    private var lastOfferSeenAt = 0L
    /** Houve aceite automático desde a última corrida finalizada? */
    private var acceptedSinceFinish = false
    private val odometer = Odometer()

    override fun onCreate() {
        super.onCreate()
        val loc = LocationProvider.get(this)
        location = loc
        if (!startInForeground(loc.hasPermission())) {
            Toast.makeText(this, "Não foi possível iniciar o copiloto", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        _running.value = true

        val ctx = createOverlayContext()
        overlayContext = ctx
        windowManager = ctx.getSystemService(WindowManager::class.java)
        voice = VoiceAnnouncer(this)

        val pipeline = OfferPipeline(this)
        lifecycleScope.launch { pipeline.run() }
        lifecycleScope.launch {
            MappApp.repos.zones.all().collect {
                zones = it
                card?.updateZones(it)
            }
        }
        lifecycleScope.launch { loc.updates().collect { card?.updateCurrentPosition(it) } }
        lifecycleScope.launch { OfferBus.evaluated.collect { render(it) } }
        lifecycleScope.launch {
            OfferBus.raw.collect { raw -> raw.category?.let { lastCategory = raw.key() to it } }
        }
        startShiftMonitor(loc)
        lifecycleScope.launch { OfferBus.tripStages.collect { onTripStage(it) } }

        // Ícone flutuante com ajustes rápidos.
        if (Settings.canDrawOverlays(this)) {
            bubble = QuickBubbleView(ctx, windowManager!!, bubbleActions).also { it.show() }
        }
        pushShift()
        lifecycleScope.launch { ReaderStatus.state.collect { bubble?.updateReader(it) } }
        lifecycleScope.launch { PinStore.pins(this@OverlayService).collect { bubble?.updatePins(it.size) } }
        lifecycleScope.launch {
            MappApp.repos.profiles.all()
                .combine(MappApp.repos.profiles.active()) { all, active -> all to active }
                .collect { (all, active) ->
                    profiles = all
                    activeProfile = active
                    bubble?.updateProfile(active, all.size.coerceAtLeast(1))
                }
        }
    }

    private val bubbleActions = object : QuickBubbleView.Actions {
        override fun previousProfile() = cycleProfile(-1)
        override fun nextProfile() = cycleProfile(+1)

        override fun updateProfile(change: (FilterProfile) -> FilterProfile) {
            lifecycleScope.launch {
                try {
                    val repo = MappApp.repos.profiles
                    val updated = change(repo.active().first())
                    val id = repo.save(updated)
                    if (updated.id == 0L && id > 0) repo.setActive(id) // perfil padrão ainda não salvo
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao salvar ajuste rápido", e)
                }
            }
        }

        override fun toggleVoice() {
            val ctx = this@OverlayService
            OverlayPrefs.setVoiceEnabled(ctx, !OverlayPrefs.isVoiceEnabled(ctx))
        }

        override fun openApp() {
            startActivity(
                Intent(this@OverlayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        override fun stopCopilot() {
            Toast.makeText(this@OverlayService, "Copiloto parado", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        override fun startShift() {
            val ctx = this@OverlayService
            val wasNew = ShiftTracker.current(ctx).value == null
            if (!location!!.hasPermission()) toast("Sem permissão de localização: os km não serão contados")
            ShiftTracker.start(ctx)
            toast(if (wasNew) "Jornada iniciada" else "Jornada retomada")
        }

        override fun pauseShift() {
            ShiftTracker.pause(this@OverlayService)
            toast("Jornada pausada")
        }

        override fun endShift() {
            val ended = ShiftTracker.end(this@OverlayService, costPerKm) ?: return
            countedKey = null
            val s = ShiftMath.summary(ended, costPerKm, System.currentTimeMillis())
            toast("Jornada encerrada: ${s.trips} corridas · lucro ${ShiftMath.money(s.profit)}")
        }

        override fun countLastOffer() = countOffer(lastOffer)

        override fun undoLastTrip() {
            val shift = ShiftTracker.current(this@OverlayService).value ?: return
            if (shift.trips.isEmpty()) { toast("Nenhuma corrida para desfazer"); return }
            ShiftTracker.undoLastTrip(this@OverlayService)
            countedOffer?.let { markAccepted(it, null) }
            countedOffer = null
            countedKey = null
            refreshCountButton()
            toast("Última corrida removida")
        }

        override fun markPin(type: ZoneType) {
            if (pinPending) return
            val loc = location ?: return
            if (!loc.hasPermission()) { toast("Permita a localização para marcar o local"); return }
            pinPending = true
            // Posição na hora do toque (GPS preciso, até 4 s); sem resposta, usa a última conhecida.
            val quick = loc.lastKnown
            lifecycleScope.launch {
                try {
                    val here = loc.current(timeoutMs = PIN_FIX_MS, precise = true) ?: quick
                    if (here == null) { toast("Sem localização agora — tente de novo"); return@launch }
                    PinStore.add(this@OverlayService, type, here)
                    toast("📍 ${type.label} marcada. Desenhe a zona depois em Zonas")
                } finally {
                    pinPending = false
                }
            }
        }
    }

    private var pinPending = false

    // ------------------------------------------------------------------ jornada

    /** Mesma oferta mesmo com endereço/tipo lidos em quadros diferentes. */
    private fun RawOffer.key(): RawOffer = identity().copy(pickupAddress = null, dropoffAddress = null)

    private fun startShiftMonitor(loc: LocationProvider) {
        lifecycleScope.launch { MappApp.repos.vehicle.config().collect { costPerKm = it.costPerKm; pushShift() } }
        lifecycleScope.launch {
            ShiftTracker.current(this@OverlayService).collect {
                pushShift()
                refreshCountButton()
            }
        }
        // Hodômetro: GPS preciso só com a jornada rodando.
        lifecycleScope.launch {
            ShiftTracker.current(this@OverlayService)
                .map { it != null && !it.paused }
                .distinctUntilChanged()
                .collectLatest { running ->
                    odometer.reset()
                    if (!running) return@collectLatest
                    loc.fixes().collect { fix ->
                        val km = odometer.add(fix)
                        if (km > 0) ShiftTracker.addKm(this@OverlayService, km)
                    }
                }
        }
        // Relógio da jornada no painel.
        lifecycleScope.launch {
            while (true) {
                delay(SHIFT_TICK_MS)
                if (ShiftTracker.current(this@OverlayService).value?.paused == false) pushShift()
            }
        }
    }

    private fun pushShift() {
        val now = System.currentTimeMillis()
        val shift: Shift? = ShiftTracker.current(this).value
        val summary = shift?.let { ShiftMath.summary(it, costPerKm, now) }
        bubble?.updateShift(shift, summary, lastOffer?.let { offerLabel(it.raw) })
        // Textos discretos junto ao "M": custo (em cima), lucro e km (embaixo) do dia.
        val today = ShiftMath.ofToday(shift, ShiftTracker.last(this).value, now)
        bubble?.updateDay(if (today === shift) summary else today?.let { ShiftMath.summary(it, costPerKm, now) })
    }

    private fun offerLabel(raw: RawOffer): String {
        val cat = categoryFor(raw)
        return listOfNotNull(cat ?: raw.platform.label, ShiftMath.money(raw.price)).joinToString(" ") +
            if (raw.key() == countedKey) " ✓ contada" else ""
    }

    private fun categoryFor(raw: RawOffer): String? =
        raw.category ?: lastCategory?.takeIf { it.first == raw.key() }?.second

    private fun countOffer(offer: EvaluatedOffer?, auto: Boolean = false) {
        if (ShiftTracker.current(this).value == null) { toast("Inicie a jornada (▶ no ícone M) para contar corridas"); return }
        val raw = offer?.raw ?: run { toast("Nenhuma oferta lida ainda"); return }
        val key = raw.key()
        if (key == countedKey) { toast("Esta corrida já foi contada"); return }
        val trip = ShiftTrip.from(raw.copy(category = categoryFor(raw)), System.currentTimeMillis())
        ShiftTracker.addTrip(this, trip)
        countedKey = key
        countedOffer = offer
        markAccepted(offer, true)
        refreshCountButton()
        pushShift()
        val label = offerLabel(raw).removeSuffix(" ✓ contada")
        toast(if (auto) "Corrida aceita — contada: $label" else "Corrida contada: $label")
    }

    /**
     * Jornada automática: "Encontro com…" na tela = a última oferta foi aceita → conta;
     * "Como foi a viagem?" = corrida finalizada.
     */
    private fun onTripStage(stage: TripStage) {
        if (stage == TripStage.ACCEPTED) {
            // Histórico: marca a oferta como aceita mesmo sem jornada aberta.
            val offer = lastOffer
            if (offer != null && System.currentTimeMillis() - lastOfferSeenAt <= ACCEPT_WINDOW_MS) markAccepted(offer, true)
        }
        if (ShiftTracker.current(this).value == null) return
        when (stage) {
            TripStage.ACCEPTED -> {
                val offer = lastOffer ?: return
                val fresh = System.currentTimeMillis() - lastOfferSeenAt <= ACCEPT_WINDOW_MS
                if (!fresh || offer.raw.key() == countedKey) return // já contada (ex.: +1) ou oferta antiga
                acceptedSinceFinish = true
                countOffer(offer, auto = true)
            }
            TripStage.FINISHED -> {
                val counted = acceptedSinceFinish || (countedKey != null && lastOffer?.raw?.key() == countedKey)
                if (counted) {
                    toast("Corrida finalizada ✓")
                } else {
                    toast("Corrida finalizada, mas não vi o aceite: toque +1 Contar última se for o caso")
                }
                acceptedSinceFinish = false
            }
        }
    }

    /** Grava no histórico de ofertas se a corrida foi aceita (aba "Aceitas hoje"). */
    private fun markAccepted(offer: EvaluatedOffer, accepted: Boolean?) {
        val key = offer.raw.key()
        if (accepted == true && key == markedAcceptedKey) return
        markedAcceptedKey = if (accepted == true) key else null
        lifecycleScope.launch {
            try {
                MappApp.repos.offers.setAccepted(offer, accepted)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao marcar oferta aceita", e)
            }
        }
    }

    private fun refreshCountButton() {
        val c = card ?: return
        val shown = lastOffer?.raw?.key()
        c.setCountState(
            when {
                ShiftTracker.current(this).value == null -> OfferCardView.CountState.HIDDEN
                shown != null && shown == countedKey -> OfferCardView.CountState.DONE
                else -> OfferCardView.CountState.READY
            },
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun cycleProfile(direction: Int) {
        val list = profiles
        if (list.size < 2) {
            Toast.makeText(this, "Crie mais perfis em Perfis de filtro", Toast.LENGTH_SHORT).show()
            return
        }
        val i = list.indexOfFirst { it.id == activeProfile.id }.coerceAtLeast(0)
        val next = list[(i + direction).mod(list.size)]
        lifecycleScope.launch {
            try {
                MappApp.repos.profiles.setActive(next.id)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao trocar perfil", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        // Sem o copiloto não há GPS: a jornada fica pausada até o próximo ▶.
        if (ShiftTracker.current(this).value?.paused == false) {
            ShiftTracker.pause(this)
            toast("Jornada pausada")
        }
        bubble?.destroy()
        bubble = null
        card?.destroy()
        card = null
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }

    private fun render(offer: EvaluatedOffer?) {
        if (offer == null) {
            card?.dismiss()
            return
        }
        // Endereço lido depois (quadro mais completo) não é oferta nova: nada de repetir a voz.
        val key = offer.raw.key()
        lastOfferSeenAt = System.currentTimeMillis()
        if (lastOffer?.raw?.key() != key) {
            lastOffer = offer
            pushShift()
        } else {
            lastOffer = offer
        }
        if (key == dismissedKey) return // usuário fechou o cartão desta oferta

        val alertZones = offer.zoneHits.filter { it.zone.alertEnabled }.toSet()
        if (key != shownKey) {
            shownKey = key
            announcedZones = alertZones
            voice?.announce(offer)
        } else if (!announcedZones.containsAll(alertZones)) {
            // Zonas chegam na 2ª etapa (depois da geocodificação): avisa só o que é novo.
            val fresh = alertZones - announcedZones
            announcedZones = announcedZones + alertZones
            voice?.announceZones(fresh.toList())
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Sem permissão de sobreposição; cartão não exibido")
            return
        }
        val c = card ?: createCard() ?: return
        refreshCountButton()
        c.show(offer, activeProfile, location?.lastKnown, zones)
    }

    private fun createCard(): OfferCardView? {
        val ctx = overlayContext ?: return null
        val wm = windowManager ?: return null
        return OfferCardView(
            ctx,
            wm,
            onClose = {
                dismissedKey = shownKey
                card?.dismiss()
            },
            onCount = { countOffer(lastOffer) },
        ).also { card = it }
    }

    private fun startInForeground(hasLocationPermission: Boolean): Boolean {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (hasLocationPermission) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> 0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível iniciar em primeiro plano", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val nm = NotificationManagerCompat.from(this)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Copiloto ativo")
                .setDescription("Aviso fixo enquanto o MApp analisa as ofertas")
                .setShowBadge(false)
                .build(),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Copiloto ativo")
            .setContentText("Analisando ofertas da Uber, 99 e inDrive. Você decide se aceita.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stop)
            .build()
    }

    /** API 30+: contexto de janela próprio para TYPE_APPLICATION_OVERLAY. */
    private fun createOverlayContext(): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }

    companion object {
        private const val TAG = "MApp/Overlay"
        private const val CHANNEL_ID = "copiloto"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "br.com.mapp.overlay.STOP"
        private const val SHIFT_TICK_MS = 30_000L
        private const val PIN_FIX_MS = 4_000L
        /** Aceite automático só vale para oferta vista há até 90 s (a tela de oferta some ao aceitar). */
        private const val ACCEPT_WINDOW_MS = 90_000L

        private val _running = MutableStateFlow(false)
        /** true enquanto o copiloto (serviço) está ativo — a tela inicial mostra o estado. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
