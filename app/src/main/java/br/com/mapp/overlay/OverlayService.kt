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
import br.com.mapp.core.card.CardValues
import br.com.mapp.core.goal.DayGoal
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.TripEvent
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.shift.TripMatcher
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneHit
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.Odometer
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.PeriodTotals
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftTrip
import br.com.mapp.finance.ReportMath
import br.com.mapp.maps.LocationProvider
import br.com.mapp.maps.PinStore
import br.com.mapp.reader.OfferAccessibilityService
import br.com.mapp.reader.ReaderStatus
import br.com.mapp.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano do copiloto: roda o [OfferPipeline] e desenha o [OfferCardView]
 * sobre os apps de corrida. Só lê e mostra — nunca toca na tela de outro app.
 */
class OverlayService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var overlayContext: Context? = null
    /** Onde cartão e ícone abrem janela (Acessibilidade ou sobreposição comum); null = sem permissão. */
    private var host: OverlayHost? = null
    private var pinCount = 0
    private var location: LocationProvider? = null
    private var voice: VoiceAnnouncer? = null
    private var greatSound: GreatSoundPlayer? = null
    /** Som de dinheiro da meta (player próprio: uma oferta chegando não corta o som). */
    private var cashSound: GreatSoundPlayer? = null
    /** Números do dia (mesma conta de Ganhos e gastos → Hoje) e meta diária, para o ícone "M". */
    private var dayTotals: PeriodTotals? = null
    private var dailyGoal: Double? = null
    /** Oferta que já tocou o som de corrida ótima (toca uma vez por oferta). */
    private var greatKey: OfferKey? = null
    private var card: OfferCardView? = null
    private var bubble: QuickBubbleView? = null
    private var notice: NoticeView? = null
    private var profiles: List<FilterProfile> = emptyList()
    private var activeProfile: FilterProfile = FilterProfile()

    private var zones: List<Zone> = emptyList()
    private var shownKey: OfferKey? = null
    private var dismissedKey: OfferKey? = null
    private var announcedZones: Set<ZoneHit> = emptySet()

    // ---- Jornada ----
    private var costPerKm: Double = 0.0
    /** Última oferta avaliada (para "+1 Contar última" depois que o cartão some). */
    private var lastOffer: EvaluatedOffer? = null
    /** Tipo lido em algum quadro da oferta atual (o OCR às vezes não lê o "UberX"). */
    private var lastCategory: Pair<OfferKey, String>? = null
    private var countedKey: OfferKey? = null
    /** Oferta contada por último (para desfazer a marca de aceita no histórico). */
    private var countedOffer: EvaluatedOffer? = null
    /** Oferta já marcada como aceita no histórico (não regravar a cada quadro). */
    private var markedAcceptedKey: OfferKey? = null
    /** Quando a [lastOffer] esteve no cartão pela última vez (para o aceite automático). */
    private var lastOfferSeenAt = 0L
    /** Quando o leitor viu a tela de corrida finalizada pela última vez. */
    private var lastFinishedAt = 0L
    /** Houve aceite automático desde a última corrida finalizada? */
    private var acceptedSinceFinish = false
    /** Qual oferta foi aceita: pelo nome do passageiro na tela (vale para corrida aceita durante outra). */
    private val matcher = TripMatcher<OfferKey>()
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
        greatSound = GreatSoundPlayer(this)
        cashSound = GreatSoundPlayer(this)

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
        startDayMonitor()
        lifecycleScope.launch { OfferBus.tripStages.collect { onTripStage(it) } }

        // Ícone flutuante com ajustes rápidos (e cartão): recriados quando o leitor liga/desliga.
        lifecycleScope.launch { OfferAccessibilityService.windowContext.collect { useHost(it) } }
        lifecycleScope.launch { ReaderStatus.state.collect { bubble?.updateReader(it) } }
        lifecycleScope.launch {
            PinStore.pins(this@OverlayService).collect {
                pinCount = it.size
                bubble?.updatePins(it.size)
            }
        }
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
            matcher.reset()
            val s = ShiftMath.summary(ended, costPerKm, System.currentTimeMillis())
            tripToast("Jornada encerrada: ${s.trips} corridas · lucro ${ShiftMath.money(s.profit)}")
        }

        override fun countLastOffer() = countOffer(lastOffer)

        override fun finishPendingTrip() = finishPending(auto = false)

        override fun cancelPendingTrip() = cancelPending(auto = false)

        override fun undoLastTrip() {
            val shift = ShiftTracker.current(this@OverlayService).value ?: return
            if (shift.trips.isEmpty()) { toast("Nenhuma corrida para desfazer"); return }
            if (!shift.trips.last().finished) matcher.closedNewest()
            ShiftTracker.undoLastTrip(this@OverlayService)
            countedOffer?.let { markAccepted(it, null) }
            countedKey?.let { matcher.forget(it) }
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
    /**
     * Identidade da oferta no cartão, na voz e na jornada: app + horário da 1ª leitura. O leitor
     * mantém esse horário quando só completa a oferta (tempo, km ou endereço lidos depois), então
     * completar não repete a voz nem vira outra corrida.
     */
    private data class OfferKey(val platform: Platform, val capturedAt: Long)

    private fun RawOffer.key(): OfferKey = OfferKey(platform, capturedAt)

    private fun startShiftMonitor(loc: LocationProvider) {
        lifecycleScope.launch { MappApp.repos.vehicle.config().collect { costPerKm = it.costPerKm; pushShift() } }
        lifecycleScope.launch {
            ShiftTracker.current(this@OverlayService).collect {
                pushShift()
                refreshCountButton()
            }
        }
        // Jornada encerrada de hoje corrigida no histórico de ofertas: atualiza o resumo junto ao "M".
        lifecycleScope.launch { ShiftTracker.last(this@OverlayService).collect { pushShift() } }
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
    }

    // ------------------------------------------------------------------ dia e meta (ícone "M")

    /**
     * Números junto ao "M" (km e custo em cima, valor bruto embaixo) e anel da meta diária: mesma
     * conta da tela Ganhos e gastos → Hoje (`PeriodMath.totals`), recalculada quando muda a jornada,
     * o histórico de ofertas, os lançamentos, a meta ou o custo/km — e de novo à meia-noite.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startDayMonitor() {
        val repos = MappApp.repos
        val ctx = this
        lifecycleScope.launch {
            todayPeriods().flatMapLatest { p ->
                val day = combine(
                    ShiftTracker.current(ctx),
                    ShiftTracker.history(ctx),
                    repos.offers.between(p.from, p.to),
                    repos.finance.entries(p.from, p.to),
                    repos.finance.goals(),
                ) { current, history, offers, entries, goals -> DayInputs(current, history, offers, entries, goals.daily) }
                combine(day, repos.vehicle.config()) { d, cost ->
                    PeriodMath.totals(p.from, p.to, d.current, d.history, d.offers, d.entries, cost.costPerKm, System.currentTimeMillis()) to d.goal
                }
            }.collect { (totals, goal) ->
                dayTotals = totals
                dailyGoal = goal
                pushDay()
                celebrateGoal(totals.gross, goal)
            }
        }
    }

    private data class DayInputs(
        val current: Shift?,
        val history: List<Shift>,
        val offers: List<OfferRecord>,
        val entries: List<FinanceEntry>,
        val goal: Double?,
    )

    /** O dia de hoje; emite de novo logo depois da meia-noite. */
    private fun todayPeriods() = flow {
        while (true) {
            val p = ReportMath.today()
            emit(p)
            delay((p.to + 1 - System.currentTimeMillis()).coerceAtLeast(0L) + DAY_ROLL_MS)
        }
    }

    private fun pushDay() {
        val t = dayTotals ?: return
        bubble?.updateDay(
            QuickBubbleView.DayInfo(
                km = t.km,
                carCost = t.carCost,
                gross = t.gross,
                goal = dailyGoal,
                hasData = t.shifts > 0 || t.gross > 0 || t.km > 0,
            ),
        )
    }

    /** Meta do dia batida: som de dinheiro + aviso, uma vez por dia e por valor de meta. */
    private fun celebrateGoal(gross: Double, goal: Double?) {
        val day = java.time.LocalDate.now().toString()
        val mark = DayGoal.celebration(gross, goal, day, OverlayPrefs.goalMark(this)) ?: return
        OverlayPrefs.setGoalMark(this, mark)
        cashSound?.playCash()
        tripToast("🎯 Meta do dia batida! ${ShiftMath.money(gross)} de ${ShiftMath.money(goal ?: gross)}")
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
        // Aceita = em andamento: só entra no lucro ao finalizar (cancelada nunca entra). Se a tela de
        // corrida finalizada já passou depois desta oferta (+1 tocado no fim), já entra finalizada.
        val alreadyFinished = !auto && lastFinishedAt > lastOfferSeenAt
        val trip = ShiftTrip.from(raw.copy(category = categoryFor(raw)), System.currentTimeMillis(), finished = alreadyFinished)
        ShiftTracker.addTrip(this, trip)
        countedKey = key
        // +1 com a corrida em andamento: o leitor passa a acompanhar o fim dela também.
        if (!auto && !alreadyFinished) matcher.manualCount(key, System.currentTimeMillis()) else matcher.markCounted(key)
        countedOffer = offer
        markAccepted(offer, true)
        refreshCountButton()
        pushShift()
        val label = offerLabel(raw).removeSuffix(" ✓ contada")
        tripToast(
            when {
                alreadyFinished -> "Corrida contada: $label"
                auto -> "Corrida aceita: $label · soma no lucro ao finalizar"
                else -> "Corrida em andamento: $label · soma no lucro ao finalizar"
            },
        )
    }

    /**
     * Jornada automática pelas telas depois da oferta:
     * - "Encontro com <nome>" / "Destino de <nome>" com passageiro NOVO = a última oferta foi aceita →
     *   conta (inclusive a aceita durante outra viagem, contada quando o novo nome aparece, e a de
     *   aceite rápido demais, contada no embarque);
     * - "Como foi a viagem?" = a corrida mais antiga em andamento terminou;
     * - fim que passou sem ser lido (passageiro novo na tela, ou "Procurando viagens"): o
     *   [TripMatcher] decide quais corridas abertas terminaram e quais foram canceladas.
     */
    private fun onTripStage(event: TripEvent) {
        val now = System.currentTimeMillis()
        val match = matcher.onStage(event, now)
        val key = match.count
        val offer = lastOffer?.takeIf { key != null && it.raw.key() == key }
        if (offer != null) {
            // Histórico: marca a oferta como aceita mesmo sem jornada aberta.
            markAccepted(offer, true)
            matcher.markCounted(key!!)
        }
        if (ShiftTracker.current(this).value == null) return
        // Corridas que terminaram sem a tela de fim ter sido lida (da mais antiga).
        repeat(match.finishedUnseen) { finishPending(auto = true, platform = match.platform) }
        if (match.finishedUnseen > 0) lastFinishedAt = now
        // Busca sem embarque que saiu da tela cedo demais para ter virado viagem: foi cancelada.
        if (match.cancelledPickup) cancelPending(auto = true, platform = match.platform)
        if (match.started && offer == null) {
            event.passenger?.let { tripToast("Viagem iniciada ✓ ${it.replaceFirstChar { c -> c.uppercase() }} embarcou") }
        }
        when (event.stage) {
            TripStage.ACCEPTED, TripStage.ON_TRIP -> {
                if (offer != null) {
                    if (offer.raw.key() == countedKey) return // já contada (ex.: +1)
                    acceptedSinceFinish = true
                    countOffer(offer, auto = true)
                } else if (match.newRide && lastOffer?.raw?.key() != countedKey) {
                    tripToast(
                        if (event.stage == TripStage.ACCEPTED) "Vi corrida aceita, mas não li a oferta dela: toque +1 se for o caso"
                        else "Vi passageiro a bordo, mas não li a oferta dela: toque +1 se for o caso",
                    )
                }
            }
            TripStage.FINISHED -> {
                lastFinishedAt = now
                if (ShiftTracker.current(this).value?.nextToFinish(event.platform) != null) {
                    finishPending(auto = true, platform = event.platform)
                } else {
                    tripToast("Corrida finalizada, mas não vi o aceite: toque +1 Contar última se for o caso")
                }
                acceptedSinceFinish = false
            }
            TripStage.IDLE -> Unit // fechamentos já aplicados acima
        }
    }

    /** Corrida aceita que não aconteceu (✕ no painel, ou outra busca começou sem este embarque). */
    private fun cancelPending(auto: Boolean, platform: Platform? = null) {
        val pending = ShiftTracker.current(this).value?.pendingTrip(platform) ?: return
        if (!auto) matcher.closedNewest()
        ShiftTracker.cancelPending(this, platform)
        countedOffer?.let { markAccepted(it, null) }
        countedKey?.let { matcher.forget(it) }
        countedOffer = null
        countedKey = null
        acceptedSinceFinish = false
        refreshCountButton()
        tripToast(
            if (auto) "Busca anterior cancelada: ${ShiftMath.money(pending.price)} fora da jornada"
            else "Corrida cancelada: ${ShiftMath.money(pending.price)} fora do lucro",
        )
    }

    /** Corrida em andamento terminou: o valor entra no ganho/lucro do dia. */
    private fun finishPending(auto: Boolean, platform: Platform? = null) {
        val pending = ShiftTracker.current(this).value?.nextToFinish(platform) ?: run {
            if (!auto) toast("Nenhuma corrida em andamento")
            return
        }
        if (!auto) matcher.closedOldest()
        ShiftTracker.finishPending(this, platform)
        tripToast("Corrida finalizada ✓ +${ShiftMath.money(pending.price)} no lucro")
    }

    private fun playGreat(key: OfferKey): Long {
        greatKey = key
        return greatSound?.play() ?: 0L
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

    /**
     * Aviso na tela por [durationMs] (o Toast some em 2 s, rápido demais para ler dirigindo).
     * Sem janela do MApp (leitor desligado e sem permissão), cai no Toast longo.
     */
    private fun toast(msg: String, durationMs: Long = NOTICE_MS) {
        val h = host
        if (h == null) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        val n = notice ?: NoticeView(h).also { notice = it }
        n.show(msg, durationMs)
    }

    /** Avisos da corrida (aceita, embarque, finalizada, cancelada): ficam mais tempo. */
    private fun tripToast(msg: String) = toast(msg, TRIP_NOTICE_MS)

    private fun cycleProfile(direction: Int) {
        val list = profiles
        if (list.size < 2) {
            toast("Crie mais perfis em Perfis de filtro")
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
            // As janelas do MApp fecham já: aqui só o Toast do sistema continua na tela.
            Toast.makeText(this, "Jornada pausada", Toast.LENGTH_LONG).show()
        }
        bubble?.destroy()
        bubble = null
        card?.destroy()
        card = null
        notice?.destroy()
        notice = null
        host = null
        voice?.shutdown()
        voice = null
        greatSound?.stop()
        greatSound = null
        cashSound?.stop()
        cashSound = null
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
        // Km e duração prevista: conferem a tela seguinte e decidem fim × cancelamento não lidos.
        matcher.offerSeen(
            key,
            lastOfferSeenAt,
            pickupKm = offer.raw.pickupKm,
            tripKm = offer.raw.tripKm,
            expectedMs = offer.metrics.totalMin?.let { it * 60_000L },
            platform = offer.raw.platform,
        )
        if (lastOffer?.raw?.key() != key) {
            lastOffer = offer
            pushShift()
        } else {
            lastOffer = offer
        }
        if (key == dismissedKey) return // usuário fechou o cartão desta oferta

        val alertZones = offer.zoneHits.filter { it.zone.alertEnabled }.toSet()
        val great = CardValues.allGreen(offer, activeProfile, OverlayPrefs.cardLayout(this))
        if (key != shownKey) {
            shownKey = key
            announcedZones = alertZones
            // Tudo verde: som de corrida ótima primeiro, a voz fala quando ele acaba.
            val soundMs = if (great) playGreat(key) else 0L
            voice?.announce(offer, soundMs)
        } else {
            if (!announcedZones.containsAll(alertZones)) {
                // Zonas chegam na 2ª etapa (depois da geocodificação): avisa só o que é novo.
                val fresh = alertZones - announcedZones
                announcedZones = announcedZones + alertZones
                voice?.announceZones(fresh.toList())
            }
            // Oferta completada depois (tempo/km lidos noutro quadro) que ficou toda verde.
            if (great && greatKey != key) playGreat(key)
        }

        val c = card ?: createCard() ?: run {
            Log.w(TAG, "Sem leitor nem permissão de sobreposição; cartão não exibido")
            return
        }
        refreshCountButton()
        c.show(offer, activeProfile, location?.lastKnown, zones)
    }

    /**
     * Escolhe onde abrir as janelas e recria ícone e cartão nela. Com o leitor conectado usa uma
     * janela da Acessibilidade (TYPE_ACCESSIBILITY_OVERLAY): a Uber esconde as sobreposições comuns
     * enquanto a solicitação está na tela, e o cartão só aparecia depois de fechá-la.
     * Sem o leitor, volta para a sobreposição comum (precisa de "Sobrepor outros apps").
     */
    private fun useHost(a11y: Context?) {
        val next = when {
            a11y != null -> a11y.getSystemService(WindowManager::class.java)?.let {
                OverlayHost(a11y, it, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            }
            Settings.canDrawOverlays(this) -> {
                val ctx = overlayContext
                val wm = windowManager
                if (ctx != null && wm != null) OverlayHost(ctx, wm, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) else null
            }
            else -> null
        }
        if (next == host) return
        bubble?.destroy()
        bubble = null
        card?.destroy()
        card = null
        notice?.destroy()
        notice = null
        host = next
        if (next == null) {
            Log.w(TAG, "Sem leitor nem permissão de sobreposição; ícone e cartão não exibidos")
            return
        }
        bubble = QuickBubbleView(next, bubbleActions).also {
            it.show()
            it.updateReader(ReaderStatus.state.value)
            it.updatePins(pinCount)
            it.updateProfile(activeProfile, profiles.size.coerceAtLeast(1))
        }
        pushShift()
        pushDay()
        // Oferta na tela durante a troca: o cartão volta na janela nova.
        val offer = OfferBus.evaluated.value ?: return
        if (offer.raw.key() == dismissedKey) return
        val c = createCard() ?: return
        refreshCountButton()
        c.show(offer, activeProfile, location?.lastKnown, zones)
    }

    private fun createCard(): OfferCardView? {
        val h = host ?: return null
        return OfferCardView(
            h,
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
        /** Folga depois da meia-noite para o dia novo começar. */
        private const val DAY_ROLL_MS = 2_000L
        private const val PIN_FIX_MS = 4_000L
        /** Avisos comuns na tela (o Toast curto era 2 s). */
        private const val NOTICE_MS = 4_000L
        /** Avisos de corrida aceita/finalizada/cancelada: tempo para ler dirigindo. */
        private const val TRIP_NOTICE_MS = 6_000L

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

/** Onde as janelas do MApp são abertas: contexto, WindowManager e tipo de janela. */
data class OverlayHost(val context: Context, val windowManager: WindowManager, val type: Int)
