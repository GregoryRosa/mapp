package br.com.mapp.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.MappConfig
import br.com.mapp.billing.PlayBilling
import br.com.mapp.core.billing.Access
import br.com.mapp.core.billing.AccessRules
import br.com.mapp.core.billing.Entitlement
import br.com.mapp.reader.ReaderStatus

/**
 * Tela de assinatura. É o que aparece **em vez do app** para quem não tem assinatura ativa.
 *
 * Mesmo bloqueado, o motorista consegue falar com o suporte (senão ele não tem como reclamar de uma
 * cobrança) e o dono consegue entrar no painel.
 */
@Composable
fun PaywallScreen(access: Access, entitlement: Entitlement, onSupport: () -> Unit, onAdmin: () -> Unit) {
    val ctx = LocalContext.current
    val offer by PlayBilling.offer.collectAsStateWithLifecycle()
    val problem by PlayBilling.problem.collectAsStateWithLifecycle()
    val version = remember { ReaderStatus.versionOf(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MappLogo(48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("MApp", style = MaterialTheme.typography.titleLarge, color = MappColors.Text)
                Text(
                    "Copiloto · versão $version",
                    style = MaterialTheme.typography.labelMedium,
                    color = MappColors.Teal,
                )
            }
        }

        GlassCard(glow = true) {
            Text(
                when (access) {
                    Access.CHECKING -> "Conferindo sua assinatura"
                    Access.STALE -> "Preciso conferir sua assinatura"
                    else -> "Assine para usar o MApp"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                AccessRules.message(access, entitlement, System.currentTimeMillis()),
                style = MaterialTheme.typography.bodyMedium,
                color = MappColors.TextDim,
            )
            val o = offer
            if (o != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    o.price + if (o.period.isNotEmpty()) " por ${o.period}" else "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MappColors.Text,
                )
                o.trialLabel?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("🎁 $it", style = MaterialTheme.typography.bodyMedium, color = MappColors.Profit)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Cobrança pela Google Play. Cancele quando quiser, direto na Play Store.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MappColors.TextDim,
                )
            }
            Spacer(Modifier.height(16.dp))
            GradientButton(if (offer?.trialLabel != null) "Começar o teste grátis" else "Assinar") {
                (ctx as? Activity)?.let { PlayBilling.buy(it) }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { PlayBilling.refresh() }) {
                    Text("Já assinei", color = MappColors.Teal)
                }
                TextButton(onClick = { openUrl(ctx, MappConfig.MANAGE_SUBSCRIPTION_URL) }) {
                    Text("Gerenciar", color = MappColors.TextDim)
                }
            }
            problem?.let {
                Text("⚠️ $it", style = MaterialTheme.typography.bodySmall, color = MappColors.Warn)
            }
        }

        GlassCard {
            Text("O que a assinatura libera", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            listOf(
                "Card da oferta com semáforo, R$/km, R$/hora e lucro líquido",
                "Mini mapa, foto da rua e satélite da coleta e do destino",
                "Jornada automática: corridas, km pelo GPS, custo e lucro",
                "Zonas de perigo e de demanda, filtros e perfis",
                "Relatórios, metas e histórico",
            ).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MappColors.TextDim)
                Spacer(Modifier.height(4.dp))
            }
        }

        Text(
            "O MApp só lê e mostra as ofertas. Ele nunca aceita ou recusa corridas por você.",
            style = MaterialTheme.typography.bodySmall,
            color = MappColors.TextDim,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSupport) { Text("Falar com o suporte", color = MappColors.Teal) }
            TextButton(onClick = onAdmin) { Text("Sou o suporte", color = MappColors.TextDim) }
        }
        TextButton(onClick = { openUrl(ctx, MappConfig.PRIVACY_URL) }) {
            Text("Política de privacidade", color = MappColors.TextDim)
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
