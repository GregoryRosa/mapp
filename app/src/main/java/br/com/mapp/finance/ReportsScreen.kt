@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappApp
import br.com.mapp.core.model.Signal
import br.com.mapp.core.shift.PeriodTotals
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.ui.BarSection
import br.com.mapp.ui.Part
import br.com.mapp.ui.WITHOUT_TRIP
import br.com.mapp.ui.WITH_TRIP
import br.com.mapp.ui.categoryColors

private enum class ReportRange(val label: String) {
    LAST_7("7 dias"),
    LAST_30("30 dias"),
    THIS_MONTH("Mês atual");

    fun period(): Period = when (this) {
        LAST_7 -> ReportMath.lastDays(7)
        LAST_30 -> ReportMath.lastDays(30)
        THIS_MONTH -> ReportMath.currentMonth()
    }
}

private const val HOUR_BAND = 3

/** Aba Relatórios da tela Financeiro. */
@Composable
internal fun ReportsTab() {
    val repos = MappApp.repos
    var range by rememberSaveable { mutableStateOf(ReportRange.LAST_7) }
    val period = remember(range) { range.period() }

    val entries by remember(period) { repos.finance.entries(period.from, period.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val offers by remember(period) { repos.offers.between(period.from, period.to) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Ganhos = lançamentos + corridas marcadas como aceitas no histórico de ofertas.
    val all = remember(entries, offers) { entries + ReportMath.ridesAsIncome(offers) }
    // Mesma conta da tela inicial e de Ganhos e gastos (jornadas + corridas fora de jornada + lançamentos).
    val totals = rememberPeriodTotals(period)
    val byWeekday = remember(all) { ReportMath.incomeByWeekday(all) }
    val byHour = remember(all) { ReportMath.incomeByHour(all, bucketHours = HOUR_BAND) }
    val offerSummary = remember(offers) { ReportMath.summarizeOffers(offers) }
    val perKmByHour = remember(offers) { ReportMath.perKmByHour(offers, bucketHours = HOUR_BAND) }

    // Semanas da Uber já fechadas (segunda 4h → segunda 4h), da mais recente para trás.
    val now = rememberNow()
    val week = ReportMath.uberWeek(now)
    val weekPeriods = remember(week) { ReportMath.closedUberWeeks(CLOSED_WEEKS, week.from + 1) }
    val weekTotals = rememberPeriodsTotals(weekPeriods, now)
    val closedWeeks = weekPeriods.zip(weekTotals).filter { !it.second.isEmpty }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportRange.entries.forEach { r ->
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
            }
        }
        Text(
            "${Format.day(period.from)} a ${Format.day(period.to)}",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            "Bruto = ${totals.rides} corridas (${Format.brl(totals.ridesGross)}) + ganhos lançados " +
                "(${Format.brl(totals.manualIncome)}). Custos = desgaste (km × custo/km) + fixos (% do valor das corridas) + gastos lançados.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Ganho bruto", Format.brl(totals.gross), Modifier.weight(1f))
            StatCard("Corridas", totals.rides.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Custos", Format.brl(totals.cost), Modifier.weight(1f))
            StatCard(
                "Líquido",
                Format.brl(totals.net),
                Modifier.weight(1f),
                valueColor = if (totals.net < 0) RedSignal else GreenSignal,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Km rodados", Format.km(totals.km.takeIf { it > 0 }), Modifier.weight(1f))
            StatCard("R$/km (bruto)", Format.perKm(totals.perKm), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("R$/hora (bruto)", Format.perHour(totals.perHour), Modifier.weight(1f))
            StatCard("R$/km médio (ofertas)", Format.perKm(offerSummary.avgPerKm), Modifier.weight(1f))
        }

        SectionTitle("Ganhos por dia da semana")
        ChartCard {
            val max = byWeekday.maxOrNull() ?: 0.0
            byWeekday.forEachIndexed { i, v ->
                BarRow(Format.WEEKDAYS[i], v, max, Format.brl(v))
            }
        }

        SectionTitle("Ganhos por horário")
        ChartCard {
            val max = byHour.maxOfOrNull { it.total } ?: 0.0
            byHour.forEach { b ->
                BarRow("%02d–%02dh".format(b.startHour, b.endHour), b.total, max, Format.brl(b.total))
            }
        }

        SectionTitle("Melhores horários (R$/km das ofertas)")
        ChartCard {
            if (perKmByHour.none { it.offers > 0 }) {
                Text("Sem ofertas com km neste período.", style = MaterialTheme.typography.bodySmall)
            } else {
                val max = perKmByHour.maxOf { it.avgPerKm ?: 0.0 }
                perKmByHour.forEach { h ->
                    BarRow(
                        "%02d–%02dh".format(h.startHour, h.endHour),
                        h.avgPerKm ?: 0.0,
                        max,
                        h.avgPerKm?.let { "${Format.perKm(it)} (${h.offers})" } ?: Format.NONE,
                        color = GreenSignal,
                        valueWidth = 108.dp,
                    )
                }
                Text("Entre parênteses: nº de ofertas vistas.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionTitle("Ofertas por semáforo")
        ChartCard {
            Text("${offerSummary.count} ofertas · ${Format.perHour(offerSummary.avgPerHour)} médio")
            val max = offerSummary.signals.values.maxOrNull()?.toDouble() ?: 0.0
            listOf(Signal.GREEN, Signal.YELLOW, Signal.RED).forEach { s ->
                val n = offerSummary.signals[s] ?: 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignalDot(s)
                    Column(Modifier.weight(1f)) {
                        BarRow(signalLabel(s), n.toDouble(), max, n.toString(), color = signalColor(s))
                    }
                }
            }
        }

        SectionTitle("Semanas fechadas")
        Text(
            "Segunda 4h até segunda 4h (fechamento da Uber) · toque para abrir",
            style = MaterialTheme.typography.bodySmall,
        )
        if (closedWeeks.isEmpty()) {
            Text("Nenhuma semana fechada com corridas ainda.", style = MaterialTheme.typography.bodySmall)
        }
        closedWeeks.forEach { (p, t) -> key(p.from) { ClosedWeekCard(p, t) } }
    }
}

/** Quantas semanas fechadas aparecem no fim de Relatórios (~6 meses). */
private const val CLOSED_WEEKS = 26

/**
 * Semana da Uber fechada: card menor (datas, ganho, corridas, tempo, km, lucro); toque abre a mesma
 * grade das jornadas, com as barras de km com/sem passageiro e dos tipos de corrida.
 */
@Composable
private fun ClosedWeekCard(p: Period, t: PeriodTotals) {
    ClosedPeriodCard(
        key = p.from,
        title = "${Format.day(p.from)} a ${Format.day(p.to)}",
        value = Format.brl(t.gross),
        summary = "${t.rides} corrida${if (t.rides == 1) "" else "s"} · ${ShiftMath.duration(t.hours)} · " +
            "${Format.km(t.km)} · lucro ${Format.brl(t.net)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatRow(
                Triple("Ganho", Format.brl(t.gross), Color.Unspecified),
                Triple("Custo", Format.brl(t.cost), RedSignal),
                Triple("Lucro", Format.brl(t.net), profitColor(t.net)),
            )
            StatRow(
                Triple("Tempo", ShiftMath.duration(t.hours), Color.Unspecified),
                Triple("Corridas", t.rides.toString(), Color.Unspecified),
                Triple("Km rodados", Format.km(t.km), Color.Unspecified),
            )
            StatRow(
                Triple("R$/km", t.perKm?.let { ShiftMath.num(it, 2) } ?: Format.NONE, Color.Unspecified),
                Triple("R$/h", t.perHour?.let { ShiftMath.num(it, 2) } ?: Format.NONE, Color.Unspecified),
                Triple("Média/corrida", t.avgTripKm?.let { Format.km(it) } ?: Format.NONE, Color.Unspecified),
            )
            if (t.km > 0 && t.kmWithTrip > 0) {
                val pct = (t.kmWithTrip.coerceAtMost(t.km) * 100 / t.km).toInt()
                BarSection(
                    title = "Km com e sem passageiro",
                    parts = listOf(
                        Part("Com passageiro ${Format.km(t.kmWithTrip)} ($pct%)", t.kmWithTrip, WITH_TRIP),
                        Part("Sem ${Format.km(t.kmWithoutTrip)}", t.kmWithoutTrip, WITHOUT_TRIP),
                    ),
                )
            }
            if (t.categories.isNotEmpty()) {
                val colors = categoryColors(t.categories.map { it.label })
                BarSection(
                    title = "Tipos de corrida",
                    parts = t.categories.map { c ->
                        Part("${c.label} ${ShiftMath.num(c.percent, 0)}% (${c.count})", c.count.toDouble(), colors.getValue(c.label))
                    },
                )
            }
        }
    }
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}
