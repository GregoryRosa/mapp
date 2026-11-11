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
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Zone
import br.com.mapp.maps.LocationProvider
import br.com.mapp.ui.MainActivity
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

    private var zones: List<Zone> = emptyList()
    private var shownKey: RawOffer? = null
    private var dismissedKey: RawOffer? = null

    override fun onCreate() {
        super.onCreate()
        val loc = LocationProvider.get(this)
        location = loc
        if (!startInForeground(loc.hasPermission())) {
            stopSelf()
            return
        }

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

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
