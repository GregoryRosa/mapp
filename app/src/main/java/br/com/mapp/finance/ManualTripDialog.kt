@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.finance

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.mapp.MappApp
import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.ShiftTrip
import br.com.mapp.core.trip.ManualTrip
import br.com.mapp.core.trip.TripCapture
import br.com.mapp.core.trip.TripDetails
import br.com.mapp.maps.GeocodingRepository
import br.com.mapp.maps.LocationProvider
import br.com.mapp.overlay.ShiftTracker
import br.com.mapp.reader.ReaderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Resultado do botão "Puxar da Uber". */
internal enum class UberPull { STARTED, READER_OFF, NO_UBER }

/**
 * "Puxar da Uber": o leitor passa a esperar a tela "Detalhes da viagem" ([TripCapture]) e a Uber abre
 * onde estava (como tocar no ícone dela). O motorista abre a viagem; o MApp lê, volta sozinho e mostra
 * o formulário preenchido. Nada é tocado na tela da Uber.
 */
internal fun pullFromUber(ctx: Context): UberPull {
    if (!ReaderStatus.state.value.connected) return UberPull.READER_OFF
    val launch = ctx.packageManager.getLaunchIntentForPackage(Platform.UBER.packageName) ?: return UberPull.NO_UBER
    TripCapture.arm(System.currentTimeMillis())
    return try {
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        UberPull.STARTED
    } catch (e: Exception) {
        TripCapture.cancel()
        UberPull.NO_UBER
    }
}

internal fun UberPull.message(): String = when (this) {
    UberPull.STARTED ->
        "Na Uber, abra a viagem (Ganhos → toque na viagem). O MApp lê a tela Detalhes da viagem e volta sozinho."
    UberPull.READER_OFF -> "Leitor de corridas desligado: ligue a Acessibilidade do MApp ou digite a viagem."
    UberPull.NO_UBER -> "App da Uber (motorista) não encontrado: digite a viagem."
}

/**
 * Grava a viagem: oferta já vista e não marcada ([match]) vira aceita; senão entra uma oferta nova aceita
 * (avaliada com o perfil e os custos de hoje, coleta/destino no mapa). As duas vão para a jornada do
 * horário, finalizadas. Devolve o aviso para o motorista.
 */
internal suspend fun saveManualTrip(ctx: Context, d: TripDetails, atMillis: Long, match: OfferRecord?): String {
    val repos = MappApp.repos
    if (match != null && match.accepted != true) {
        val raw = match.evaluated.raw
        repos.offers.setAcceptedById(match.id, true)
        val inShift = ShiftTracker.setTrip(ctx, ShiftTrip.from(raw, raw.capturedAt), raw.capturedAt, true)
        return "Oferta das ${Format.time(raw.capturedAt)} marcada como aceita" + shiftNote(inShift)
    }
    // Horário livre: o histórico troca a oferta com o mesmo app e horário.
    val taken = repos.offers.between(atMillis, atMillis + 100).first()
        .filter { it.evaluated.raw.platform == d.platform }
        .map { it.evaluated.raw.capturedAt }
        .toSet()
    var at = atMillis
    while (at in taken) at++
    val raw = ManualTrip.offer(d, at)

    val profile = orDefault(FilterProfile()) { repos.profiles.active().first() }
    val cost = orDefault(VehicleCostConfig()) { repos.vehicle.config().first() }
    val zones = orDefault(emptyList()) { repos.zones.all().first() }
    val geocoder = GeocodingRepository.get(ctx)
    val near = LocationProvider.get(ctx).lastKnown
    val (pickup, dropoff) = coroutineScope {
        val p = async { withTimeoutOrNull(GEOCODE_MS) { geocoder.geocode(raw.pickupAddress, near) } }
        val q = async { withTimeoutOrNull(GEOCODE_MS) { geocoder.geocode(raw.dropoffAddress, near) } }
        p.await() to q.await()
    }
    val evaluated = OfferEvaluator.evaluate(raw, profile, cost, zones, pickup, dropoff)
    val id = repos.offers.record(evaluated)
    repos.offers.setAcceptedById(id, true)
    val inShift = ShiftTracker.setTrip(ctx, ShiftTrip.from(raw, at), at, true)
    return "Viagem inserida" + shiftNote(inShift)
}

private fun shiftNote(inShift: Boolean) =
    if (inShift) " e somada à jornada" else ". Nenhuma jornada nesse horário: conta no histórico e nos relatórios"

private suspend fun <T> orDefault(default: T, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

private const val GEOCODE_MS = 4_000L

/**
 * Formulário da viagem inserida à mão. [fromUber] = veio lida da tela da Uber (só conferir). Avisa se a
 * viagem já está no histórico: aceita (inserir de novo conta em dobro) ou vista sem marcar (marca ela).
 */
@Composable
internal fun ManualTripDialog(
    initial: TripDetails,
    fromUber: Boolean,
    onDismiss: () -> Unit,
    onPull: () -> Unit,
    onSave: (TripDetails, Long, OfferRecord?) -> Unit,
) {
    var platform by remember { mutableStateOf(initial.platform) }
    var category by remember { mutableStateOf(initial.category.orEmpty()) }
    var date by remember {
        mutableStateOf(ManualTrip.formatDateTime(initial.atMillis ?: System.currentTimeMillis()))
    }
    var price by remember { mutableStateOf(Format.input(initial.price)) }
    var km by remember { mutableStateOf(Format.input(initial.km)) }
    var minutes by remember { mutableStateOf(initial.minutes?.toString().orEmpty()) }
    var pickup by remember { mutableStateOf(initial.pickup.orEmpty()) }
    var dropoff by remember { mutableStateOf(initial.dropoff.orEmpty()) }

    val at = ManualTrip.parseDateTime(date)
    val priceValue = Format.parse(price)?.takeIf { it > 0 }
    val kmValue = Format.parse(km)
    val minValue = minutes.trim().toIntOrNull()
    val valid = at != null && priceValue != null &&
        (km.isBlank() || (kmValue != null && kmValue >= 0)) &&
        (minutes.isBlank() || (minValue != null && minValue > 0))

    // A mesma viagem já está no histórico? (mesmo app e valor, perto do horário)
    var match by remember { mutableStateOf<OfferRecord?>(null) }
    LaunchedEffect(platform, priceValue, at) {
        match = if (at == null || priceValue == null) {
            null
        } else {
            orDefault(null) {
                val around = MappApp.repos.offers
                    .between(at - ManualTrip.MATCH_WINDOW_MS, at + ManualTrip.MATCH_WINDOW_MS).first()
                ManualTrip.match(TripDetails(platform = platform, price = priceValue, atMillis = at), around)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inserir viagem") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (fromUber) {
                        "Lido da tela Detalhes da viagem da Uber. Confira e toque em Inserir."
                    } else {
                        "Viagem que o MApp não pegou. Entra no histórico como aceita e na jornada do horário."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!fromUber) {
                    TextButton(onClick = onPull) { Text("📲 Puxar os dados da Uber") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Platform.entries.forEach { p ->
                        FilterChip(selected = platform == p, onClick = { platform = p }, label = { Text(p.label) })
                    }
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Tipo (UberX, Comfort, Electric…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Data e hora (dd/mm/aaaa hh:mm)") },
                    singleLine = true,
                    isError = at == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                MoneyField("Valor", price, { price = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Distância", km, { km = it }, Modifier.weight(1f), suffix = "km")
                    NumberField("Duração", minutes, { minutes = it }, Modifier.weight(1f), suffix = "min", integer = true)
                }
                OutlinedTextField(
                    value = pickup,
                    onValueChange = { pickup = it },
                    label = { Text("Coleta (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dropoff,
                    onValueChange = { dropoff = it },
                    label = { Text("Destino (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (initial.stops.isNotEmpty()) {
                    val n = initial.stops.size
                    Text("Com $n ${if (n == 1) "parada" else "paradas"} no caminho.", style = MaterialTheme.typography.bodySmall)
                }
                match?.let { m ->
                    val seen = m.evaluated.raw
                    Text(
                        if (m.accepted == true) {
                            "⚠️ Essa viagem já está no histórico como aceita (${Format.brl(seen.price)} às " +
                                "${Format.time(seen.capturedAt)}). Inserir de novo conta em dobro."
                        } else {
                            "A oferta dessa viagem foi vista às ${Format.time(seen.capturedAt)} e não foi marcada: " +
                                "ela é que vai ser marcada como aceita."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (m.accepted == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val time = at ?: return@TextButton
                    onSave(
                        initial.copy(
                            platform = platform,
                            price = priceValue,
                            category = category.trim().ifEmpty { null },
                            atMillis = time,
                            km = kmValue?.takeIf { it > 0 },
                            seconds = minValue?.let { it * 60 },
                            pickup = pickup.trim().ifEmpty { null },
                            dropoff = dropoff.trim().ifEmpty { null },
                        ),
                        time,
                        match,
                    )
                },
            ) {
                val m = match
                Text(
                    when {
                        m == null -> "Inserir"
                        m.accepted == true -> "Inserir mesmo assim"
                        else -> "Marcar como aceita"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
