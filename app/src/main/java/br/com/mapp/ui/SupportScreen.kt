@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappConfig
import br.com.mapp.billing.EntitlementStore
import br.com.mapp.core.billing.AccessRules
import br.com.mapp.core.support.DeviceReport
import br.com.mapp.core.support.SupportMessage
import br.com.mapp.core.support.SupportRules
import br.com.mapp.core.support.Ticket
import br.com.mapp.core.support.TicketKind
import br.com.mapp.finance.BackTopBar
import br.com.mapp.overlay.OverlayService
import br.com.mapp.reader.ReaderStatus
import br.com.mapp.support.SupportRepository
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Suporte do motorista: abre chamado (🐞 bug ou 💬 dúvida) e conversa com o suporte dentro do app.
 * O painel que responde é o [AdminScreen], só para a conta do dono.
 */
@Composable
fun SupportScreen(onBack: () -> Unit, onAdmin: () -> Unit) {
    val ctx = LocalContext.current
    val backendOn = remember { SupportRepository.available(ctx) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(backendOn) { if (backendOn) SupportRepository.ensureSession(ctx) { error = it } }

    val signedIn by SupportRepository.signedIn.collectAsStateWithLifecycle()
    val tickets by remember(signedIn) {
        if (signedIn) SupportRepository.myTickets(ctx) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    var open by remember { mutableStateOf<String?>(null) }

    val current = tickets.firstOrNull { it.id == open }
    if (current != null) {
        ChatScreen(
            title = SupportRules.label(current.kind) + " · " + SupportRules.label(current.status),
            subject = current.subject,
            ticketId = current.id,
            fromSupport = false,
            onBack = { open = null },
        )
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BackTopBar("Suporte", onBack) {
                // Entrada do painel de chamados: quem não é o dono não passa do login.
                TextButton(onClick = onAdmin) { Text("Painel", color = MappColors.TextDim) }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!backendOn) {
                GlassCard {
                    Text("Chat indisponível nesta versão", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Fale comigo por e-mail: ${MappConfig.SUPPORT_EMAIL}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MappColors.TextDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${MappConfig.SUPPORT_EMAIL}"))
                        i.putExtra(Intent.EXTRA_SUBJECT, "MApp – suporte")
                        i.putExtra(Intent.EXTRA_TEXT, "\n\n---\n" + deviceLine(ctx))
                        runCatching { ctx.startActivity(i) }
                    }) { Text("Abrir e-mail", color = MappColors.Teal) }
                }
                return@Column
            }

            NewTicketCard(
                onSend = { kind, text ->
                    SupportRepository.open(kind, text, deviceLine(ctx)) { id, err ->
                        error = err
                        if (id != null) open = id
                    }
                },
            )

            error?.let {
                Text("⚠️ $it", style = MaterialTheme.typography.bodySmall, color = MappColors.Warn)
            }

            if (tickets.isNotEmpty()) {
                SectionTitle("Meus chamados")
                SupportRules.forUser(tickets).forEach { t ->
                    TicketRow(t, showUid = false) { open = t.id }
                }
            }

            Text(
                "O que eu recebo junto do chamado: versão do MApp, modelo do celular, versão do Android, " +
                    "se o leitor está ligado e se a assinatura está ativa. Nenhuma corrida, endereço, valor " +
                    "ou localização é enviado.",
                style = MaterialTheme.typography.bodySmall,
                color = MappColors.TextDim,
            )
        }
    }
}

/** Abrir chamado: escolhe bug ou dúvida, escreve e manda. */
@Composable
private fun NewTicketCard(onSend: (TicketKind, String) -> Unit) {
    var kind by remember { mutableStateOf(TicketKind.BUG) }
    var text by remember { mutableStateOf("") }
    GlassCard(glow = true) {
        Text("Abrir chamado", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindChip("🐞 Reportar bug", kind == TicketKind.BUG, Modifier.weight(1f)) { kind = TicketKind.BUG }
            KindChip("💬 Falar com o suporte", kind == TicketKind.HELP, Modifier.weight(1f)) { kind = TicketKind.HELP }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= SupportRules.MAX_MESSAGE) text = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (kind == TicketKind.BUG) "O que aconteceu? (app, tela, o que você esperava)"
                    else "Como posso ajudar?",
                )
            },
            minLines = 3,
            maxLines = 8,
        )
        Spacer(Modifier.height(10.dp))
        GradientButton("Enviar", trailing = "") {
            if (SupportRules.clean(text) != null) {
                onSend(kind, text)
                text = ""
            }
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(44.dp)
            .border(1.dp, if (selected) MappColors.Teal else MappColors.Border, shape)
            .background(if (selected) Color(0x335EE6D8) else Color(0x14FFFFFF), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MappColors.Text else MappColors.TextDim,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** Uma linha da lista de chamados (serve para o motorista e para o painel). */
@Composable
internal fun TicketRow(t: Ticket, showUid: Boolean, onClick: () -> Unit) {
    GlassCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SupportRules.label(t.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MappColors.Lilac,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                SupportRules.label(t.status),
                style = MaterialTheme.typography.labelMedium,
                color = if (t.waitingSupport) MappColors.Warn else MappColors.TextDim,
            )
            Spacer(Modifier.weight(1f))
            if (t.unreadForUser) {
                Box(Modifier.size(9.dp).background(MappColors.Loss, RoundedCornerShape(50)))
                Spacer(Modifier.width(6.dp))
            }
            Text(shortTime(t.updatedAt), style = MaterialTheme.typography.labelSmall, color = MappColors.TextDim)
        }
        Spacer(Modifier.height(4.dp))
        Text(t.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text(
            SupportRules.summary(t),
            style = MaterialTheme.typography.bodySmall,
            color = MappColors.TextDim,
            maxLines = 1,
        )
        if (showUid && t.device.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(t.device, style = MaterialTheme.typography.labelSmall, color = MappColors.Periwinkle, maxLines = 2)
        }
    }
}

/**
 * A conversa. A mesma tela serve para o motorista e para o painel — muda só [fromSupport], que
 * decide o lado da bolha e quem assina a mensagem.
 */
@Composable
internal fun ChatScreen(
    title: String,
    subject: String,
    ticketId: String,
    fromSupport: Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val messages by remember(ticketId) {
        SupportRepository.messages(ctx, ticketId)
    }.collectAsStateWithLifecycle(emptyList())
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Abrir a conversa tira a bolinha de resposta nova.
    LaunchedEffect(ticketId) { if (!fromSupport) SupportRepository.markRead(ticketId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { BackTopBar(title, onBack, actions) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Text(
                subject,
                style = MaterialTheme.typography.bodyMedium,
                color = MappColors.TextDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 2,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MappColors.Teal, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                items(messages, key = { it.id }) { m -> Bubble(m, mine = m.fromSupport == fromSupport) }
            }
            error?.let {
                Text(
                    "⚠️ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.Warn,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= SupportRules.MAX_MESSAGE) text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mensagem") },
                    maxLines = 5,
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        if (SupportRules.clean(text) != null) {
                            SupportRepository.send(ticketId, text, fromSupport) { error = it }
                            text = ""
                        }
                    },
                ) { Icon(Icons.Filled.Send, contentDescription = "Enviar", tint = MappColors.Teal) }
            }
        }
    }
}

@Composable
private fun Bubble(m: SupportMessage, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .bubbleWidth()
                .background(
                    if (mine) Color(0x335EE6D8) else Color(0x1FFFFFFF),
                    RoundedCornerShape(16.dp),
                )
                .border(1.dp, if (mine) MappColors.Border else Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) {
                Text(
                    if (m.fromSupport) "Suporte" else "Motorista",
                    style = MaterialTheme.typography.labelSmall,
                    color = MappColors.Lilac,
                )
            }
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                shortTime(m.at),
                style = MaterialTheme.typography.labelSmall,
                color = MappColors.TextDim,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** Largura máxima da bolha: 82% da tela, para a conversa não virar uma linha só. */
private fun Modifier.bubbleWidth(): Modifier = this.fillMaxWidth(0.82f)

/** "14:32" para hoje, "17/11 14:32" para outros dias. */
internal fun shortTime(at: Long): String {
    if (at <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    val fmt = if (time.toLocalDate() == today) "HH:mm" else "dd/MM HH:mm"
    return time.format(DateTimeFormatter.ofPattern(fmt))
}

/** Linha de diagnóstico que vai anexada ao chamado. */
internal fun deviceLine(ctx: android.content.Context): String {
    val e = EntitlementStore.state.value
    val access = AccessRules.access(e, System.currentTimeMillis(), SupportRepository.owner.value)
    return DeviceReport.text(
        appVersion = ReaderStatus.versionOf(ctx),
        androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        readerConnected = ReaderStatus.state.value.connected,
        copilotRunning = OverlayService.running.value,
        subscription = access.name.lowercase(),
    )
}
