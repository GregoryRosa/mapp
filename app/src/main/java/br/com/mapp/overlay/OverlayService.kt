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
import br.com.mapp.core.model.Zone
import br.com.mapp.maps.LocationProvider
import br.com.mapp.reader.ReaderStatus
import br.com.mapp.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

        // Ícone flutuante com ajustes rápidos.
        if (Settings.canDrawOverlays(this)) {
            bubble = QuickBubbleView(ctx, windowManager!!, bubbleActions).also { it.show() }
        }
        lifecycleScope.launch { ReaderStatus.state.collect { bubble?.updateReader(it) } }
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
    }

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
        val key = offer.raw.identity()
        if (key == dismissedKey) return // usuário fechou o cartão desta oferta

        if (key != shownKey) {
            shownKey = key
            voice?.announce(offer)
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Sem permissão de sobreposição; cartão não exibido")
            return
        }
        val c = card ?: createCard() ?: return
        c.show(offer, location?.lastKnown, zones)
    }

    private fun createCard(): OfferCardView? {
        val ctx = overlayContext ?: return null
        val wm = windowManager ?: return null
        return OfferCardView(ctx, wm) {
            dismissedKey = shownKey
            card?.dismiss()
        }.also { card = it }
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
