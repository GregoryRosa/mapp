@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.mapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappConfig
import br.com.mapp.core.support.SupportRules
import br.com.mapp.core.support.TicketStatus
import br.com.mapp.finance.BackTopBar
import br.com.mapp.support.SupportRepository
import kotlinx.coroutines.flow.flowOf

/**
 * Painel de chamados — **só o dono entra**. O e-mail do dono está em
 * [br.com.mapp.MappConfig.OWNER_EMAIL] e quem garante o acesso de verdade são as regras do
 * Firestore (README → Firestore): mesmo com o APK modificado, outra conta não lê os chamados.
 *
 * Com a conta do dono logada, o app também abre sem assinatura (o dono não assina de si mesmo).
 */
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val backendOn = remember { SupportRepository.available(ctx) }
    val owner by SupportRepository.owner.collectAsStateWithLifecycle()
    val tickets by remember(owner) {
        if (owner) SupportRepository.allTickets(ctx) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    var open by remember { mutableStateOf<String?>(null) }

    val current = tickets.firstOrNull { it.id == open }
    if (current != null) {
        ChatScreen(
            title = "Chamado · " + SupportRules.label(current.status),
            subject = listOfNotNull(
                current.subject,
                current.screen.takeIf { it.isNotBlank() }?.let { "📍 Aberto em: $it" },
                current.device.takeIf { it.isNotBlank() },
            ).joinToString("\n"),
            ticketId = current.id,
            fromSupport = true,
            onBack = { open = null },
            actions = {
                TextButton(
                    onClick = {
                        SupportRepository.setStatus(
                            current.id,
                            if (current.status == TicketStatus.RESOLVED) TicketStatus.ANSWERED
                            else TicketStatus.RESOLVED,
                        )
                    },
                ) {
                    Text(
                        if (current.status == TicketStatus.RESOLVED) "Reabrir" else "Resolvido",
                        color = MappColors.Teal,
                    )
                }
            },
        )
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BackTopBar("Painel de chamados", onBack) {
                if (owner) {
                    TextButton(onClick = { SupportRepository.signOutOwner() }) {
                        Text("Sair", color = MappColors.TextDim)
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!backendOn) {
                GlassCard {
                    Text("Falta configurar o Firebase", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Coloque o arquivo google-services.json em app/ e publique de novo. O passo a passo está " +
                            "no README do projeto.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MappColors.TextDim,
                    )
                }
                return@Column
            }
            if (!owner) {
                OwnerLogin()
                return@Column
            }

            val waiting = SupportRules.waitingForAdmin(tickets)
            GlassCard(glow = true) {
                Text(
                    if (waiting == 0) "Nenhum chamado esperando" else "$waiting chamado(s) esperando resposta",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${tickets.size} no total · a fila começa por quem esperou mais",
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.TextDim,
                )
            }
            if (tickets.isEmpty()) {
                Text(
                    "Os chamados dos motoristas aparecem aqui em tempo real.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.TextDim,
                )
            }
            SupportRules.forAdmin(tickets).forEach { t ->
                TicketRow(t, showUid = true) { open = t.id }
            }
        }
    }
}

/** Login do suporte: e-mail do dono + senha criada no Firebase Authentication. */
@Composable
private fun OwnerLogin() {
    val ctx = LocalContext.current
    var email by remember { mutableStateOf(MappConfig.OWNER_EMAIL) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Se já existir sessão do dono neste aparelho, nem mostra o login.
    LaunchedEffect(Unit) { SupportRepository.ensureSession(ctx) }

    GlassCard(glow = true) {
        Text("Entrar como suporte", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Só a conta do dono do MApp abre este painel.",
            style = MaterialTheme.typography.bodySmall,
            color = MappColors.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("E-mail") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Senha") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text("⚠️ $it", style = MaterialTheme.typography.bodySmall, color = MappColors.Warn)
        }
        Spacer(Modifier.height(12.dp))
        GradientButton(if (busy) "Entrando…" else "Entrar", trailing = "") {
            if (!busy && password.length >= 6) {
                busy = true
                error = null
                SupportRepository.signInOwner(email, password) { err ->
                    busy = false
                    error = err
                }
            } else if (password.length < 6) {
                error = "A senha tem pelo menos 6 letras/números."
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                "A senha é a que você criou em Firebase → Authentication → Users.",
                style = MaterialTheme.typography.labelSmall,
                color = MappColors.TextDim,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}
