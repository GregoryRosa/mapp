package br.com.mapp.ui

import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.mapp.MappConfig

/**
 * **Aviso destacado** sobre o serviço de Acessibilidade, com aceite explícito — exigência da
 * política do Google Play para app que usa a API de Acessibilidade sem ser ferramenta de
 * acessibilidade (não pode estar só na política de privacidade, e precisa de uma ação do usuário).
 *
 * Aparece na primeira abertura, antes de qualquer permissão, e some depois do aceite
 * ([Consent.accepted]). Quem não aceita não liga o leitor.
 */
@Composable
fun ConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MappLogo(44.dp)
            Spacer(Modifier.width(12.dp))
            Text("Antes de começar", style = MaterialTheme.typography.titleLarge, color = MappColors.Text)
        }

        GlassCard(glow = true) {
            Text("Como o MApp lê as ofertas", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Para mostrar o Card com R$/km, R$/hora e lucro no momento em que a corrida chega, o MApp usa o " +
                    "serviço de Acessibilidade do Android. Com ele o MApp:",
                style = MaterialTheme.typography.bodyMedium,
                color = MappColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "lê o texto da tela dos apps de corrida (Uber, 99 e inDrive) e só deles;",
                "quando o app de corrida não entrega a oferta como texto, captura a imagem daquela janela e " +
                    "reconhece o texto dentro do próprio celular;",
                "desenha o Card por cima do app de corrida.",
            ).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MappColors.TextDim)
                Spacer(Modifier.height(6.dp))
            }
        }

        GlassCard {
            Text("O que o MApp NÃO faz", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            listOf(
                "Nunca toca na tela por você: não aceita e não recusa corrida.",
                "Não lê nenhum outro app do celular (banco, mensagens, navegador).",
                "Não salva nem envia as imagens da tela — elas existem só na memória, por instantes.",
                "Suas corridas, ganhos, zonas e relatórios ficam no seu celular.",
            ).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MappColors.TextDim)
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Sai do celular apenas o que você escrever no chat de suporte, junto da versão do app e do " +
                    "modelo do aparelho.",
                style = MaterialTheme.typography.bodySmall,
                color = MappColors.Periwinkle,
            )
        }

        GradientButton("Entendi e aceito") {
            Consent.accept(ctx)
            onAccept()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { openUrl(ctx, MappConfig.PRIVACY_URL) }) {
                Text("Política de privacidade", color = MappColors.TextDim)
            }
            TextButton(onClick = onDecline) { Text("Não aceito", color = MappColors.TextDim) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Guarda o aceite do aviso de Acessibilidade. */
object Consent {
    private const val PREFS = "mapp_ui"
    private const val KEY = "accessibility_consent_v1"

    fun accepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun accept(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}
