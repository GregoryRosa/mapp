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
import br.com.mapp.Gate
import br.com.mapp.MappApp
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.card.CardFilters
import br.com.mapp.core.card.CardValues
import br.com.mapp.core.route.RideRoute
import br.com.mapp.core.sound.OfferSound
import br.com.mapp.core.sound.OfferSounds
import br.com.mapp.core.goal.DayGoal
import br.com.mapp.core.goal.GoalPlan
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.TripEvent
import br.com.mapp.core.model.TripStage
import br.com.mapp.core.shift.AutoShift
import br.com.mapp.core.shift.AutoShiftAction
import br.com.mapp.core.shift.RideStartCue
import br.com.mapp.core.shift.TripMatcher
import br.com.mapp.core.model.Zone
import br.com.mapp.core.model.ZoneHit
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.ActiveRides
import br.com.mapp.core.shift.CostPart
import br.com.mapp.core.shift.CostSplit
import br.com.mapp.core.shift.ManualRide
import br.com.mapp.core.shift.Odometer
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.PeriodTotals
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftTrip
import br.com.mapp.finance.FixedCostsSync
import br.com.mapp.finance.ReportMath
import br.com.mapp.maps.LocationProvider
import br.com.mapp.maps.PinStore
import br.com.mapp.reader.OfferAccessibilityService
import br.com.mapp.core.status.ReaderCheck
import br.com.mapp.reader.ReaderStatus
import br.com.mapp.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
    /** Oferta que já tocou o aviso de parada (uma vez por oferta). */
    private var stopAlertKey: OfferKey? = null
    private var card: OfferCardView? = null
    private var bubble: QuickBubbleView? = null
    private var notice: NoticeView? = null
    private var profiles: List<FilterProfile> = emptyList()
    private var activeProfile: FilterProfile = FilterProfile()

    private var zones: List<Zone> = emptyList()
    private var shownKey: OfferKey? = null
    private var dismissedKey: OfferKey? = null
    private var announcedZones: Set<ZoneHit> = emptySet()

    /** Nomes de filtros do Card já ditos pela voz nesta oferta (filtro que casa depois é dito uma vez). */
    private var announcedFilters: Set<String> = emptySet()

    // ---- Jornada ----
    /** Desgaste: R$ por km rodado. */
    private var costPerKm: Double = 0.0
    /** Custos fixos (seguro, financiamento...): fração do valor de cada corrida finalizada (0,25 = 25%). */
    private var fixedShare: Double = 0.0
    /** Jornada, corridas finalizadas e dia da última conta do rateio dos fixos (refaz quando mudam). */
    private var fixedKey: Triple<Long?, Int, Long>? = null
    /** Divisão do custo por item de Custos fixos (gravada na jornada ao encerrar). */
    private var costParts: List<CostPart> = emptyList()
    /** Última oferta avaliada (para o +1 do Card e o aceite automático). */
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
    /**
     * Ofertas recentes por identidade: a memória do [matcher] devolve a chave da oferta aceita (pode
     * não ser a última — corrida aceita na fila) e a oferta completa vem daqui.
     */
    private val recentOffers = LinkedHashMap<OfferKey, EvaluatedOffer>()
    /** Última versão da memória do aceite salva no aparelho ([TripMatcher.changes]). */
    private var savedMatcher = -1
    private var savedMatcherAt = 0L
    /** Memória salva já foi lida (antes disso não grava por cima — ex.: serviço que nem chegou a abrir). */
    private var memoryReady = false
    private val odometer = Odometer()
    /** Notificação no celular a cada corrida aceita (rota + mapa ao expandir). */
    private var rides: RideNotifier? = null

    /** Oferta + tipo da corrida aceita (os dados da notificação), para o menu Atividades. */
    private data class Accepted(val offer: EvaluatedOffer, val category: String?)

    /** Corridas aceitas em andamento: atual e próxima no menu Atividades do "M". */
    private val activeRides = ActiveRides<OfferKey, Accepted>()

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
        rides = RideNotifier(this, lifecycleScope)
        // Memória do aceite: volta ao reabrir o copiloto (o Android pode matar o serviço no meio da
        // viagem) — antes das ofertas novas chegarem. As ofertas dela vêm do histórico (Room).
        restoreTripMemory()
        TripLog.add(this, "— Copiloto ligado · v${ReaderStatus.versionOf(this)} · ${matcher.describe()} · ${shiftLine()}")
        val preload = lifecycleScope.async { preloadOffers() }

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
        // Corrida contada sem o tipo (lido num quadro depois, ou oferta restaurada da memória): o tipo
        // vem da oferta gravada no histórico — sem "Outros" na jornada.
        lifecycleScope.launch {
            combine(ShiftTracker.current(this@OverlayService), MappApp.repos.offers.recent(100)) { shift, offers -> shift to offers }
                .collect { (shift, offers) ->
                    if (shift?.trips?.any { it.category.isNullOrBlank() } == true) {
                        ShiftTracker.fillCategories(this@OverlayService, offers.map { it.evaluated.raw })
                    }
                }
        }
        startShiftMonitor(loc)
        startDayMonitor()
        // Telas lidas enquanto as ofertas do histórico carregam esperam na fila.
        lifecycleScope.launch { OfferBus.tripStages.collect { preload.await(); onTripStage(it) } }
        // "Hoje" da tela inicial da Uber maior que as corridas contadas: a diferença vira ganho extra.
        val uberExtra = UberExtraSync(this, ridesOpen = { matcher.openRides }, notify = { toast(it, TRIP_NOTICE_MS) })
        lifecycleScope.launch { OfferBus.dayEarnings.collect { preload.await(); uberExtra.onReading(it) } }

        // Ícone flutuante "M" com o menu circular (e cartão): recriados quando o leitor liga/desliga.
        lifecycleScope.launch { OfferAccessibilityService.windowContext.collect { useHost(it) } }
        lifecycleScope.launch { ReaderStatus.state.collect { bubble?.updateReader(it) } }
        // Leitor sem conexão (ícone cinza com "!"): o aviso diz o motivo sozinho, sem precisar tocar no "M".
        // A espera evita avisar à toa quando o Android reconecta o leitor logo em seguida (ex.: ao atualizar).
        lifecycleScope.launch {
            ReaderStatus.state.map { it.connected }.distinctUntilChanged().collectLatest { connected ->
                if (connected) return@collectLatest
                delay(READER_WARNING_DELAY_MS)
                ReaderCheck.notice(ReaderStatus.problem(this@OverlayService))?.let { toast(it, READER_WARNING_MS) }
            }
        }
        lifecycleScope.launch {
            MappApp.repos.profiles.all()
                .combine(MappApp.repos.profiles.active()) { all, active -> all to active }
                .collect { (all, active) ->
                    profiles = all
                    activeProfile = active
                }
        }
    }

    private val bubbleActions = object : QuickBubbleView.Actions {

        override fun toggleVoice() {
            val ctx = this@OverlayService
            OverlayPrefs.setVoiceEnabled(ctx, !OverlayPrefs.isVoiceEnabled(ctx))
        }

        override fun notice(message: String, durationMs: Long) = toast(message, durationMs)

        override fun openApp() {
            startActivity(
                Intent(this@OverlayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        override fun stopCopilot() {
            val ctx = this@OverlayService
            snoozeAutoStart(ctx)
            Toast.makeText(ctx, stoppedMessage(ctx), Toast.LENGTH_LONG).show()
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
            val ended = ShiftTracker.end(this@OverlayService, costPerKm, costParts, fixedShare) ?: return
            // Km da jornada entram na média que divide os custos do mês (Custos fixos).
            FixedCostsSync.afterShiftEnded(this@OverlayService)
            countedKey = null
            matcher.reset()
            saveTripMemory(force = true)
            val s = ShiftMath.summary(ended, costPerKm, System.currentTimeMillis(), fixedShare)
            tripToast("Jornada encerrada: ${s.trips} corridas · lucro ${ShiftMath.money(s.profit)}")
        }

        override fun activities(): RideActivities {
            val now = System.currentTimeMillis()
            val inShift = ShiftTracker.current(this@OverlayService).value?.trips?.count { !it.finished } ?: 0
            // Abertas = as N aceitas mais recentes (as corridas terminam da mais antiga para a mais nova).
            val (current, next) = activeRides.current(maxOf(matcher.openRides, inShift), now)
            fun ActiveRides.Ride<OfferKey, Accepted>.toActivity() = RideActivity(
                key = key,
                offer = data.offer,
                category = data.category ?: categoryFor(data.offer.raw),
                passenger = passenger,
                onBoard = onBoard,
                acceptedAt = acceptedAt,
            )
            return RideActivities(current?.toActivity(), next?.toActivity(), location?.lastKnown, zones)
        }

        override fun rideState(): ManualRide.State =
            ManualRide.state(ShiftTracker.current(this@OverlayService).value, matcher.openRides)

        override fun finishRide() = closeRideManually(finished = true)

        override fun cancelRide() = closeRideManually(finished = false)

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

    private fun OfferKey.encode(): String = "${platform.name}:$capturedAt"

    private fun decodeKey(text: String): OfferKey? {
        val (p, at) = text.split(':').takeIf { it.size == 2 } ?: return null
        val platform = runCatching { Platform.valueOf(p) }.getOrNull() ?: return null
        return at.toLongOrNull()?.let { OfferKey(platform, it) }
    }

    /** Guarda a oferta na memória (a mais nova por último; as mais velhas saem primeiro). */
    private fun remember(offer: EvaluatedOffer) {
        val key = offer.raw.key()
        recentOffers.remove(key)
        recentOffers[key] = offer
        while (recentOffers.size > TripMatcher.MAX_OFFERS) {
            val oldest = recentOffers.keys.minByOrNull { it.capturedAt } ?: break
            recentOffers.remove(oldest)
        }
    }

    /**
     * Salva a memória do aceite quando ela muda (oferta nova, tela de corrida, contagem) ou, com a
     * oferta no cartão, a cada poucos segundos. [force] = salva agora.
     */
    private fun saveTripMemory(force: Boolean = false) {
        if (!memoryReady) return
        val now = System.currentTimeMillis()
        if (!force && matcher.changes == savedMatcher && now - savedMatcherAt < SAVE_MEMORY_MS) return
        savedMatcher = matcher.changes
        savedMatcherAt = now
        OverlayPrefs.setTripMemory(this, matcher.snapshot(now) { it.encode() })
    }

    private fun restoreTripMemory() {
        memoryReady = true
        val now = System.currentTimeMillis()
        if (matcher.restore(OverlayPrefs.tripMemory(this), now) { decodeKey(it) }) {
            savedMatcher = matcher.changes
            savedMatcherAt = now
            Log.i(TAG, "Memória do aceite restaurada: ${matcher.rememberedOffers} ofertas, ${matcher.openRides} corridas abertas")
        }
    }

    /**
     * Ofertas da memória que este serviço não viu (copiloto reaberto): vêm do histórico gravado. A
     * mais nova, se recente, volta a ser a "última oferta" do +1.
     */
    private suspend fun preloadOffers() {
        val now = System.currentTimeMillis()
        val records = try {
            MappApp.repos.offers.between(now - TripMatcher.MAX_RESTORE_MS, now).first()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao carregar ofertas recentes", e)
            return
        }
        records.asSequence()
            .map { it.evaluated }
            .sortedByDescending { it.raw.capturedAt }
            .take(TripMatcher.MAX_OFFERS)
            .filter { it.raw.key() !in recentOffers }
            .forEach { remember(it) }
        // Menu Atividades depois de reabrir o copiloto: as aceitas recentes voltam (o acompanhamento
        // restaurado diz quantas continuam abertas — ficam as mais novas).
        records.filter { it.accepted == true }
            .map { it.evaluated }
            .sortedBy { it.raw.capturedAt }
            .forEach { o ->
                val key = o.raw.key()
                if (activeRides.data(key) == null) activeRides.accepted(key, Accepted(o, o.raw.category), at = o.raw.capturedAt)
            }
        if (lastOffer == null) {
            val newest = recentOffers.values.maxByOrNull { it.raw.capturedAt }
            if (newest != null && now - newest.raw.capturedAt <= LAST_OFFER_RESTORE_MS) {
                lastOffer = newest
                lastOfferSeenAt = newest.raw.capturedAt
            }
        }
    }

    /**
     * Rateio dos custos fixos ([FixedCostsSync]): refeito ao ligar o copiloto, quando a jornada abre ou
     * encerra, quando uma corrida termina (ou sai) e quando o dia vira — não a cada km do GPS.
     */
    private fun refreshFixedCosts(shift: Shift?) {
        val key = Triple(shift?.startedAt, shift?.trips?.count { it.finished } ?: 0, java.time.LocalDate.now().toEpochDay())
        if (key == fixedKey) return
        fixedKey = key
        FixedCostsSync.refresh(this)
    }

    private fun startShiftMonitor(loc: LocationProvider) {
        lifecycleScope.launch {
            MappApp.repos.vehicle.config().collect {
                costPerKm = it.costPerKm
                fixedShare = it.fixedShare
                costParts = CostSplit.parts(it)
                pushShift()
            }
        }
        lifecycleScope.launch {
            ShiftTracker.current(this@OverlayService).collect {
                pushShift()
                refreshCountButton()
                refreshFixedCosts(it)
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
        bubble?.updateShift(ShiftTracker.current(this).value)
        // Corrida aceita aberta no acompanhamento: "M" verde mesmo sem jornada aberta.
        bubble?.updateRide(matcher.openRides > 0)
        // Corrida que terminou/começou: menu Atividades aberto atualiza.
        bubble?.refreshActivities()
    }

    // ------------------------------------------------------------------ dia e meta (ícone "M")

    /**
     * Números junto ao "M" (km e custo em cima, valor bruto embaixo) e anel da meta diária (de **lucro
     * líquido**: bruto − custo do carro − gastos lançados, como em Ganhos e gastos): mesma
     * conta da tela Ganhos e gastos → Hoje (`PeriodMath.totals`), recalculada quando muda a jornada,
     * o histórico de ofertas, os lançamentos, a meta ou o custo/km — e de novo à meia-noite.
     * **Dia de trabalho:** com jornada aberta vale o dia em que ela começou (`ReportMath.workDay`):
     * passar da meia-noite rodando não zera os números nem a meta; o dia vira quando a jornada acaba.
     * A meta é a parte da **meta mensal** nesse dia (`GoalPlan.dayGoal`, a mesma conta da semana) ou,
     * sem meta mensal, a do dia da semana (`WeekGoals.goalFor`).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startDayMonitor() {
        val repos = MappApp.repos
        val ctx = this
        // Dia de trabalho: muda quando a jornada abre/fecha e à meia-noite (sem jornada aberta).
        val workDays = combine(
            ShiftTracker.current(ctx).map { it?.startedAt }.distinctUntilChanged(),
            midnights(),
        ) { openStartedAt, _ -> ReportMath.workDay(openStartedAt) }.distinctUntilChanged()
        lifecycleScope.launch {
            workDays.flatMapLatest { p ->
                val day = combine(
                    ShiftTracker.current(ctx),
                    ShiftTracker.history(ctx),
                    repos.offers.between(p.from, p.to),
                    repos.finance.entries(p.from, p.to),
                    repos.finance.goals(),
                ) { current, history, offers, entries, goals ->
                    // Meta do dia de trabalho: parte da mensal (ou a do dia da semana, sem mensal).
                    DayInputs(current, history, offers, entries, GoalPlan.dayGoal(goals, ReportMath.dateOf(p.from)))
                }
                combine(day, repos.vehicle.config()) { d, cost ->
                    val t = PeriodMath.totals(
                        p.from, p.to, d.current, d.history, d.offers, d.entries, cost.costPerKm,
                        System.currentTimeMillis(), fixedShare = cost.fixedShare,
                    )
                    Triple(p, t, d.goal)
                }
            }.collect { (p, totals, goal) ->
                dayTotals = totals
                dailyGoal = goal
                pushDay()
                celebrateGoal(ReportMath.dateOf(p.from), totals.net, goal)
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

    /** Emite agora e logo depois de cada meia-noite (o dia do calendário mudou). */
    private fun midnights() = flow {
        while (true) {
            val today = ReportMath.today()
            emit(today.from)
            delay((today.to + 1 - System.currentTimeMillis()).coerceAtLeast(0L) + DAY_ROLL_MS)
        }
    }

    private fun pushDay() {
        val t = dayTotals ?: return
        bubble?.updateDay(
            QuickBubbleView.DayInfo(
                km = t.km,
                carCost = t.carCost,
                gross = t.gross,
                net = t.net,
                goal = dailyGoal,
                hasData = t.shifts > 0 || t.gross > 0 || t.km > 0,
            ),
        )
    }

    /**
     * Meta do dia batida (lucro líquido): som de dinheiro + aviso, uma vez por dia de trabalho e por
     * valor de meta (a meia-noite com a jornada aberta não repete o som).
     */
    private fun celebrateGoal(workDate: java.time.LocalDate, net: Double, goal: Double?) {
        val mark = DayGoal.celebration(net, goal, workDate.toString(), OverlayPrefs.goalMark(this)) ?: return
        OverlayPrefs.setGoalMark(this, mark)
        cashSound?.playGoal() // som escolhido nos ajustes do botão "M"
        tripToast("🎯 Meta do dia batida! Lucro de ${ShiftMath.money(net)} de ${ShiftMath.money(goal ?: net)}")
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
        saveTripMemory(force = true)
        countedOffer = offer
        // +1 tocado com a corrida já terminada: não manda a notificação da rota.
        markAccepted(offer, true, notify = !alreadyFinished)
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
        try {
            handleTripStage(event)
        } finally {
            saveTripMemory(force = true)
            pushShift()
            if (stageLogged) TripLog.add(this, "  → ${matcher.describe()} · ${shiftLine()}")
        }
    }

    /** A última tela entrou no registro? (tela inicial repetida sem efeito não entra) */
    private var stageLogged = false

    /** Jornada em uma linha para o registro: últimas corridas e quais estão em andamento. */
    private fun shiftLine(): String {
        val shift = ShiftTracker.current(this).value ?: return "sem jornada"
        val trips = shift.trips.takeLast(4).joinToString(", ") { ShiftMath.money(it.price) + if (it.finished) " ✓" else " em andamento" }
        return "jornada${if (shift.paused) " pausada" else ""}=[$trips]"
    }

    /** Tela lida + decisão do acompanhamento, para o registro. */
    private fun logStage(event: TripEvent, match: TripMatcher.Result<OfferKey>, offer: EvaluatedOffer?) {
        val screen = when (event.stage) {
            TripStage.ACCEPTED -> "Encontro com"
            TripStage.ON_TRIP -> "Destino de"
            TripStage.FINISHED -> "Como foi a viagem?"
            TripStage.IDLE -> "Procurando viagens"
        }
        val decided = listOfNotNull(
            offer?.let { "conta ${ShiftMath.money(it.raw.price)}" },
            "oferta fora da memória".takeIf { match.count != null && offer == null },
            "corrida nova".takeIf { match.newRide },
            "embarcou".takeIf { match.started },
            "fecha ${match.finishedUnseen} anterior(es)".takeIf { match.finishedUnseen > 0 },
            "busca anterior cancelada".takeIf { match.cancelledPickup },
            "avaliação da anterior (não fecha)".takeIf { match.lateFinish },
        ).joinToString(", ")
        // "Procurando viagens" se repete a cada 30 s parado: só entra quando fecha alguma corrida.
        stageLogged = !(event.stage == TripStage.IDLE && decided.isEmpty())
        if (!stageLogged) return
        val km = event.km?.let { " ${ShiftMath.km(it)}" }.orEmpty()
        TripLog.add(this, "Tela ${event.platform?.label.orEmpty()} \"$screen ${event.passenger.orEmpty()}\"$km: ${decided.ifEmpty { "nada muda" }}")
    }

    private fun handleTripStage(event: TripEvent) {
        val now = System.currentTimeMillis()
        val match = matcher.onStage(event, now)
        val key = match.count
        // A oferta aceita vem da memória (não só a última: a da fila pode ter ficado para trás).
        val offer = key?.let { recentOffers[it] }
        logStage(event, match, offer)
        if (offer != null) {
            // Histórico: marca a oferta como aceita mesmo sem jornada aberta (e notifica no celular).
            markAccepted(offer, true, passenger = event.passenger, onBoard = event.stage == TripStage.ON_TRIP)
            matcher.markCounted(key!!)
        }
        // Corrida começando: a jornada inicia/retoma sozinha para a corrida entrar nos números.
        autoShift(event, match, offer)
        rideStartCue(event, match, offer)
        if (ShiftTracker.current(this).value == null) {
            // Sem jornada: a corrida fica em "Aceitas hoje" e no dia, e o "M" fica verde até ela acabar.
            if (offer != null && event.stage != TripStage.FINISHED) {
                tripToast("Corrida aceita: ${offerLabel(offer.raw)} · fora da jornada (▶ no M para iniciar)")
            }
            return
        }
        // Corridas que terminaram sem a tela de fim ter sido lida (da mais antiga).
        // Passageiro novo na tela (troca para a corrida da fila): o aviso diz que é a ANTERIOR que terminou.
        repeat(match.finishedUnseen) { finishPending(auto = true, platform = match.platform, previous = match.newRide) }
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
                        if (event.stage == TripStage.ACCEPTED) "Vi corrida aceita, mas não li a oferta dela: toque +1 no Card se for o caso"
                        else "Vi passageiro a bordo, mas não li a oferta dela: marque em Ofertas → Todas se for o caso",
                    )
                }
            }
            TripStage.FINISHED -> {
                // Avaliação atrasada da corrida anterior (já finalizada na troca para a da fila): a
                // corrida aceita continua em andamento.
                if (match.lateFinish) {
                    Log.i(TAG, "Avaliação da corrida anterior: corrida em andamento mantida")
                    return
                }
                lastFinishedAt = now
                if (ShiftTracker.current(this).value?.nextToFinish(event.platform) != null) {
                    finishPending(auto = true, platform = event.platform)
                } else {
                    tripToast("Corrida finalizada, mas não vi o aceite: marque em Ofertas → Todas se for o caso")
                }
                acceptedSinceFinish = false
            }
            TripStage.IDLE -> Unit // fechamentos já aplicados acima
        }
    }

    /**
     * Jornada automática ([AutoShift]): corrida começando (indo buscar o passageiro ou já a bordo) com
     * a jornada fechada ou pausada → ela inicia/retoma sozinha, como se o ▶ tivesse sido tocado.
     */
    private fun autoShift(event: TripEvent, match: TripMatcher.Result<OfferKey>, offer: EvaluatedOffer?) {
        val rideStarted = offer != null || match.newRide || match.started
        val action = AutoShift.action(ShiftTracker.current(this).value, event.stage, rideStarted)
        if (action == AutoShiftAction.NONE) return
        if (location?.hasPermission() == false) toast("Sem permissão de localização: os km não serão contados")
        ShiftTracker.start(this)
        val what = if (action == AutoShiftAction.START) "iniciada" else "retomada"
        tripToast("▶ Jornada $what sozinha: corrida começou")
        TripLog.add(this, "  → jornada $what sozinha (corrida começou)")
    }

    /**
     * Aviso de corrida iniciada ([RideStartCue]): bolha estourando no som e faíscas saindo do ícone
     * "M", uma vez quando a corrida começa (indo buscar o passageiro, ou no embarque se o aceite não
     * foi lido). Vale também sem jornada aberta; som e efeito são escolhidos nos ajustes do botão.
     */
    private fun rideStartCue(event: TripEvent, match: TripMatcher.Result<OfferKey>, offer: EvaluatedOffer?) {
        val rideStarted = offer != null || match.newRide || match.started
        val now = System.currentTimeMillis()
        if (!RideStartCue.shouldCue(event.stage, rideStarted, lastRideCueAt, now)) return
        lastRideCueAt = now
        greatSound?.playRide()
        bubble?.celebrateRideStart()
    }

    /** Quando o aviso de corrida iniciada tocou (para não repetir no mesmo aceite). */
    private var lastRideCueAt: Long? = null

    /**
     * **Encerrar corrida à mão** (seção "Corrida em andamento" do painel ⚙ do ícone "M"):
     * [finished] = true → a corrida aberta mais antiga terminou e entra no ganho/lucro;
     * false → a mais recente não aconteceu e sai da jornada (o caso do **+1 tocado sem querer**).
     *
     * Corrida que só o acompanhamento do aceite vê (aceita com a jornada fechada, "M" verde sem
     * jornada) é fechada só nele — não há valor a somar em jornada nenhuma.
     */
    private fun closeRideManually(finished: Boolean) {
        val shift = ShiftTracker.current(this).value
        val pending = if (finished) shift?.nextToFinish else shift?.pendingTrip
        if (pending != null) {
            TripLog.add(this, "Painel: corrida ${if (finished) "finalizada" else "tirada"} à mão (${ShiftMath.money(pending.price)})")
            if (finished) finishPending(auto = false) else cancelPending(auto = false)
            return
        }
        if (matcher.openRides == 0) {
            toast("Nenhuma corrida em andamento")
            return
        }
        val now = System.currentTimeMillis()
        if (finished) {
            matcher.closedOldest(now)
        } else {
            matcher.closedNewest(now)
            // Tira a marca de aceita (aba "Aceitas hoje"), a notificação da rota e o menu Atividades.
            countedOffer?.let { markAccepted(it, null) }
            countedKey?.let { matcher.forget(it) }
            countedOffer = null
            countedKey = null
        }
        acceptedSinceFinish = false
        saveTripMemory(force = true)
        refreshCountButton()
        pushShift()
        TripLog.add(this, "Painel: corrida fora da jornada ${if (finished) "finalizada" else "tirada"} à mão")
        tripToast(
            if (finished) "Corrida finalizada ✓ (estava fora da jornada: nada a somar no lucro)"
            else "Corrida tirada ✓ não estava na jornada",
        )
    }

    /** Corrida aceita que não aconteceu (✕ no painel, ou outra busca começou sem este embarque). */
    private fun cancelPending(auto: Boolean, platform: Platform? = null) {
        val pending = ShiftTracker.current(this).value?.pendingTrip(platform) ?: return
        if (!auto) { matcher.closedNewest(System.currentTimeMillis()); saveTripMemory(force = true) }
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
    private fun finishPending(auto: Boolean, platform: Platform? = null, previous: Boolean = false) {
        val pending = ShiftTracker.current(this).value?.nextToFinish(platform) ?: run {
            if (!auto) toast("Nenhuma corrida em andamento")
            return
        }
        if (!auto) { matcher.closedOldest(System.currentTimeMillis()); saveTripMemory(force = true) }
        ShiftTracker.finishPending(this, platform)
        tripToast("${if (previous) "Corrida anterior finalizada" else "Corrida finalizada"} ✓ +${ShiftMath.money(pending.price)} no lucro")
    }

    private fun playGreat(key: OfferKey): Long {
        greatKey = key
        return greatSound?.play() ?: 0L
    }

    /** Aviso de corrida com parada no caminho (som escolhido na tela do Card). Nunca recusa nada. */
    private fun playStopAlert(key: OfferKey): Long {
        stopAlertKey = key
        return greatSound?.playStop() ?: 0L
    }

    /**
     * Grava no histórico de ofertas se a corrida foi aceita (aba "Aceitas hoje") e manda a notificação
     * da corrida aceita (rota + mapa) — ou a tira, quando a corrida é desmarcada.
     * [passenger]/[onBoard] = nome e etapa lidos na tela de aceite (null/false no +1).
     */
    private fun markAccepted(
        offer: EvaluatedOffer,
        accepted: Boolean?,
        passenger: String? = null,
        onBoard: Boolean = false,
        notify: Boolean = true,
    ) {
        val key = offer.raw.key()
        if (accepted == true && key == markedAcceptedKey) return
        markedAcceptedKey = if (accepted == true) key else null
        if (accepted == true) {
            if (notify) {
                rides?.accepted(key, offer, categoryFor(offer.raw), passenger, onBoard, location?.lastKnown, zones)
                // Corrida em andamento: entra no menu Atividades (+1 com a corrida já terminada, não).
                activeRides.accepted(key, Accepted(offer, categoryFor(offer.raw)), System.currentTimeMillis(), passenger, onBoard)
            }
        } else {
            rides?.cancel(key)
            activeRides.removed(key)
        }
        bubble?.refreshActivities()
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
    private fun tripToast(msg: String) {
        TripLog.add(this, "  Aviso: $msg")
        toast(msg, TRIP_NOTICE_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // "Parar" na notificação: parado à mão, o automático espera a Uber ficar offline/online.
            snoozeAutoStart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // O copiloto é a parte paga: sem assinatura ativa ele não sobe (nem quando o Android
        // reinicia o serviço sozinho por causa do START_STICKY). A decisão é do core/billing.
        if (!Gate.isOpen(this)) {
            Toast.makeText(this, "Assinatura do MApp inativa: abra o app para assinar.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_AUTO)?.let { app ->
            TripLog.add(this, "▶ Copiloto ligado sozinho: $app aberta e online")
            toast("▶ Copiloto ligado sozinho ($app online)")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        saveTripMemory(force = true)
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
            pickupMin = offer.raw.pickupMin,
        )
        remember(offer)
        saveTripMemory()
        if (lastOffer?.raw?.key() != key) {
            val r = offer.raw
            TripLog.add(
                this,
                "Oferta ${offerLabel(r)} · busca ${r.pickupKm?.let(ShiftMath::km) ?: "?"}/${r.pickupMin ?: "?"} min" +
                    " · viagem ${r.tripKm?.let(ShiftMath::km) ?: "?"}/${r.tripMin ?: "?"} min · ${matcher.describe()}",
            )
            lastOffer = offer
            pushShift()
        } else {
            lastOffer = offer
        }
        // Corrida já aceita que chegou completada (endereço localizado depois): atualiza a notificação
        // e o menu Atividades.
        rides?.updated(key, offer, categoryFor(offer.raw))
        activeRides.data(key)?.let { old ->
            if (activeRides.updated(key, Accepted(offer, categoryFor(offer.raw) ?: old.category))) bubble?.refreshActivities()
        }
        if (key == dismissedKey) return // usuário fechou o cartão desta oferta

        val alertZones = offer.zoneHits.filter { it.zone.alertEnabled }.toSet()
        val great = CardValues.allGreen(offer, activeProfile, OverlayPrefs.cardLayout(this))
        val hasStops = RideRoute.stopCount(offer.raw) > 0
        val stopSound = OverlayPrefs.stopSound(this)
        // Filtros do Card (Card → Filtros) que casam e têm "falar o nome".
        val spokenFilters = CardFilters.spoken(CardFilters.matching(OverlayPrefs.cardFilters(this), offer))
        if (key != shownKey) {
            shownKey = key
            announcedZones = alertZones
            announcedFilters = spokenFilters.toSet()
            // Um som antes da voz: aviso de parada (se ligado) ou, tudo verde, som de corrida ótima.
            val soundMs = when (OfferSounds.pick(hasStops, great, stopSound)) {
                OfferSound.STOP -> playStopAlert(key)
                OfferSound.GREAT -> playGreat(key)
                null -> 0L
            }
            voice?.announce(offer, soundMs, spokenFilters)
        } else {
            // Filtro que só casou agora (endereço ou número lido noutro quadro): diz só o que é novo.
            val freshFilters = spokenFilters.filter { it !in announcedFilters }
            if (freshFilters.isNotEmpty()) {
                announcedFilters = announcedFilters + freshFilters
                voice?.announceFilters(freshFilters)
            }
            if (!announcedZones.containsAll(alertZones)) {
                // Zonas chegam na 2ª etapa (depois da geocodificação): avisa só o que é novo.
                val fresh = alertZones - announcedZones
                announcedZones = announcedZones + alertZones
                voice?.announceZones(fresh.toList())
            }
            // Oferta completada depois (outro quadro leu a parada, o tempo ou o km).
            when (OfferSounds.pick(hasStops, great, stopSound)) {
                OfferSound.STOP -> if (stopAlertKey != key) playStopAlert(key)
                OfferSound.GREAT -> if (greatKey != key) playGreat(key)
                null -> Unit
            }
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
        bubble = QuickBubbleView(next, bubbleActions, lifecycleScope).also {
            it.show()
            it.updateReader(ReaderStatus.state.value)
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
        fun tryStart(t: Int): Boolean = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), t)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível iniciar em primeiro plano (tipo $t)", e)
            false
        }
        if (tryStart(type)) return true
        // Ligado sozinho (Uber online) com o MApp em segundo plano: se o Android recusar o tipo
        // localização, sobe sem ele em vez de não ligar.
        val withLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
        return withLocation && tryStart(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        /** Ligado pelo copiloto automático ([CopilotAutoStart]): nome do app de corrida que ficou online. */
        private const val EXTRA_AUTO = "br.com.mapp.overlay.AUTO"
        private const val SHIFT_TICK_MS = 30_000L
        /** Folga depois da meia-noite para o dia novo começar. */
        private const val DAY_ROLL_MS = 2_000L
        private const val PIN_FIX_MS = 4_000L
        /** Avisos comuns na tela (o Toast curto era 2 s). */
        private const val NOTICE_MS = 4_000L
        /** Leitor continua sem conexão depois disto: o aviso do "!" aparece sozinho. */
        private const val READER_WARNING_DELAY_MS = 2_500L
        private const val READER_WARNING_MS = 10_000L
        /** Avisos de corrida aceita/finalizada/cancelada: tempo para ler dirigindo. */
        private const val TRIP_NOTICE_MS = 6_000L
        /** Memória do aceite salva no máx. a cada isto enquanto a mesma oferta está no cartão. */
        private const val SAVE_MEMORY_MS = 3_000L
        /** Copiloto reaberto: a oferta do histórico mais nova volta a ser a "última" do +1 se tiver até isto. */
        private const val LAST_OFFER_RESTORE_MS = 60 * 60_000L

        private val _running = MutableStateFlow(false)
        /** true enquanto o copiloto (serviço) está ativo — a tela inicial mostra o estado. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Ligar à mão (tela inicial): acaba a pausa do copiloto automático. */
        fun start(ctx: Context) {
            OverlayPrefs.setAutoStartSnoozedAt(ctx, 0L)
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        /**
         * Copiloto automático ([CopilotAutoStart]): a Uber abriu online. Chamado com o MApp em segundo
         * plano — o leitor de Acessibilidade conectado é o que permite ao Android subir o serviço.
         */
        fun startAuto(ctx: Context, platform: Platform) {
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, OverlayService::class.java).putExtra(EXTRA_AUTO, platform.label),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Copiloto automático recusado pelo Android", e)
                TripLog.add(ctx, "⚠ ${platform.label} online, mas o Android não deixou ligar o copiloto sozinho")
            }
        }

        /** Parar à mão (tela inicial). */
        fun stop(ctx: Context) {
            snoozeAutoStart(ctx)
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        /** Parado à mão: o automático não religa até a Uber ficar offline e online de novo. */
        private fun snoozeAutoStart(ctx: Context) {
            OverlayPrefs.setAutoStartSnoozedAt(ctx, System.currentTimeMillis())
        }

        /** Aviso de copiloto parado à mão (diz quando ele volta sozinho). */
        fun stoppedMessage(ctx: Context): String =
            if (OverlayPrefs.isAutoStartOn(ctx)) {
                "Copiloto parado. Liga sozinho de novo quando você ficar offline e online na Uber."
            } else {
                "Copiloto parado"
            }
    }
}

/** Onde as janelas do MApp são abertas: contexto, WindowManager e tipo de janela. */
data class OverlayHost(val context: Context, val windowManager: WindowManager, val type: Int)
