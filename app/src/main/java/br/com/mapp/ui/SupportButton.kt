package br.com.mapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.mapp.core.support.SupportRules
import br.com.mapp.support.SupportRepository
import kotlinx.coroutines.flow.flowOf

/**
 * Botão de suporte que fica **no canto de todas as telas** (desenhado uma vez por cima do NavHost,
 * em `MainActivity`). Leva para o chat com o suporte e para o report de bug.
 *
 * Fica no meio da borda direita de propósito: o topo é da barra de título, o rodapé é do menu da
 * tela inicial e dos botões flutuantes do Financeiro. A bolinha mostra quando o suporte respondeu.
 */
@Composable
fun BoxScope.SupportFab(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val signedIn by SupportRepository.signedIn.collectAsStateWithLifecycle()
    val tickets by remember(signedIn) {
        if (signedIn) SupportRepository.myTickets(ctx) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val unread = SupportRules.unreadForUser(tickets)

    Box(
        Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xE60B161D))
                .background(Brush.linearGradient(listOf(Color(0x335EE6D8), Color(0x33B59CFF))))
                .border(1.dp, MappColors.Border, CircleShape)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.SupportAgent,
                contentDescription = "Suporte e report de bug",
                tint = MappColors.Teal,
                modifier = Modifier.size(24.dp),
            )
        }
        if (unread > 0) {
            Box(
                Modifier
                    .size(18.dp)
                    .offset(x = 16.dp, y = (-16).dp)
                    .clip(CircleShape)
                    .background(MappColors.Loss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 9) "9+" else unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2B0006),
                )
            }
        }
    }
}
