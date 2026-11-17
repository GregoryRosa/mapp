package br.com.mapp.support

import android.content.Context
import android.util.Log
import br.com.mapp.MappConfig
import br.com.mapp.core.support.SupportMessage
import br.com.mapp.core.support.SupportRules
import br.com.mapp.core.support.Ticket
import br.com.mapp.core.support.TicketKind
import br.com.mapp.core.support.TicketStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Chat de suporte e report de bug, em cima do Firestore.
 *
 * **O que sai do celular:** só o que o motorista escreve no chat, mais a linha de diagnóstico
 * (versão do MApp, modelo do celular, versão do Android, leitor ligado/desligado e situação da
 * assinatura). Nada de corrida, endereço, valor, GPS ou imagem — a regra 4 do CLAUDE.md continua
 * valendo para o resto do app.
 *
 * O motorista entra com **login anônimo** (não precisa criar conta nem dar e-mail). O suporte (o
 * dono) entra com e-mail e senha; as regras do Firestore (README) só deixam esse e-mail ler os
 * chamados dos outros e escrever como suporte.
 *
 * Sem `app/google-services.json` o app compila e roda normalmente: o chat aparece desligado, com o
 * e-mail de contato no lugar ([available]).
 */
object SupportRepository {

    private const val TAG = "MappSupport"
    private const val TICKETS = "tickets"
    private const val MESSAGES = "messages"
    private const val MAX_TICKETS = 200L

    private val _signedIn = MutableStateFlow(false)

    /** Já existe sessão (anônima ou do suporte). */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _owner = MutableStateFlow(false)

    /** A sessão atual é a do dono (libera o painel de chamados e o app sem assinatura). */
    val owner: StateFlow<Boolean> = _owner.asStateFlow()

    private var configured = false

    /** O Firebase está configurado neste APK? (precisa do `google-services.json`). */
    fun available(ctx: Context): Boolean {
        if (configured) return true
        configured = FirebaseApp.getApps(ctx.applicationContext).isNotEmpty()
        return configured
    }

    private fun auth(): FirebaseAuth = FirebaseAuth.getInstance()

    private fun db(): FirebaseFirestore = FirebaseFirestore.getInstance()

    private fun uid(): String? = auth().currentUser?.uid

    /**
     * Garante uma sessão. Chamado ao abrir a tela de suporte: login anônimo é instantâneo e não
     * pede nada ao motorista.
     */
    fun ensureSession(ctx: Context, onError: (String) -> Unit = {}) {
        if (!available(ctx)) return
        val user = auth().currentUser
        if (user != null) {
            _signedIn.value = true
            _owner.value = isOwnerEmail(user.email)
            return
        }
        auth().signInAnonymously()
            .addOnSuccessListener {
                _signedIn.value = true
                _owner.value = false
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "login anônimo", e)
                onError(
                    "Não deu para conectar ao suporte. Confira a internet. " +
                        "(Se o erro continuar, o login anônimo pode estar desligado no Firebase.)",
                )
            }
    }

    private fun isOwnerEmail(email: String?): Boolean =
        email != null && email.trim().equals(MappConfig.OWNER_EMAIL, ignoreCase = true)

    /** Login do suporte (só o e-mail do dono abre o painel). */
    fun signInOwner(email: String, password: String, onDone: (String?) -> Unit) {
        if (!configured) {
            onDone("Chat de suporte indisponível nesta versão.")
            return
        }
        if (!isOwnerEmail(email)) {
            onDone("Esta conta não é a do suporte.")
            return
        }
        auth().signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener {
                _signedIn.value = true
                _owner.value = isOwnerEmail(auth().currentUser?.email)
                onDone(null)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "login suporte", e)
                onDone("E-mail ou senha não conferem.")
            }
    }

    /** Sai da conta do suporte e volta para a sessão anônima. */
    fun signOutOwner(ctx: Context) {
        if (!configured) return
        auth().signOut()
        _owner.value = false
        _signedIn.value = false
        ensureSession(ctx)
    }

    /**
     * Abre um chamado com a primeira mensagem. Devolve o id pelo callback; a escrita fica na fila do
     * Firestore se estiver sem internet (o motorista não precisa esperar).
     */
    fun open(
        kind: TicketKind,
        message: String,
        device: String,
        onDone: (String?, String?) -> Unit,
    ) {
        if (!configured) {
            onDone(null, "Chat de suporte indisponível nesta versão.")
            return
        }
        val text = SupportRules.clean(message)
        val user = uid()
        if (text == null) {
            onDone(null, "Escreva um pouco mais para eu entender o problema.")
            return
        }
        if (user == null) {
            onDone(null, "Ainda conectando ao suporte. Tente de novo em alguns segundos.")
            return
        }
        val now = System.currentTimeMillis()
        val doc = db().collection(TICKETS).document()
        val data = hashMapOf(
            "uid" to user,
            "kind" to kind.name,
            "subject" to SupportRules.subjectOf(text),
            "status" to TicketStatus.OPEN.name,
            "createdAt" to now,
            "updatedAt" to now,
            "lastMessage" to text,
            "lastFromSupport" to false,
            "unreadForUser" to false,
            "waitingSupport" to true,
            "device" to device,
        )
        doc.set(data)
            .addOnFailureListener { e -> Log.w(TAG, "abrir chamado", e) }
        doc.collection(MESSAGES).document().set(messageData(text, false, now))
        onDone(doc.id, null)
    }

    /** Manda uma mensagem num chamado que já existe. [fromSupport] vem do painel. */
    fun send(ticketId: String, message: String, fromSupport: Boolean, onError: (String) -> Unit = {}) {
        if (!configured) return
        val text = SupportRules.clean(message) ?: return
        val now = System.currentTimeMillis()
        val ticket = db().collection(TICKETS).document(ticketId)
        ticket.collection(MESSAGES).document().set(messageData(text, fromSupport, now))
            .addOnFailureListener { e ->
                Log.w(TAG, "enviar", e)
                onError("Não deu para enviar. Confira a internet.")
            }
        val update = hashMapOf<String, Any>(
            "updatedAt" to now,
            "lastMessage" to text,
            "lastFromSupport" to fromSupport,
            "unreadForUser" to fromSupport,
            "waitingSupport" to !fromSupport,
            "status" to (if (fromSupport) TicketStatus.ANSWERED else TicketStatus.OPEN).name,
        )
        ticket.update(update)
    }

    private fun messageData(text: String, fromSupport: Boolean, at: Long) = hashMapOf(
        "text" to text,
        "fromSupport" to fromSupport,
        "at" to at,
    )

    /** O motorista abriu o chamado: tira a bolinha de resposta nova. */
    fun markRead(ticketId: String) {
        if (!configured) return
        db().collection(TICKETS).document(ticketId)
            .update(mapOf("unreadForUser" to false))
    }

    /** Painel: marcar resolvido ou reabrir. */
    fun setStatus(ticketId: String, status: TicketStatus) {
        if (!configured) return
        db().collection(TICKETS).document(ticketId).update(
            mapOf(
                "status" to status.name,
                "updatedAt" to System.currentTimeMillis(),
                "waitingSupport" to (status != TicketStatus.RESOLVED),
            ),
        )
    }

    /** Chamados do motorista (tempo real). Ordem feita em Kotlin para não precisar de índice. */
    fun myTickets(ctx: Context): Flow<List<Ticket>> {
        if (!available(ctx)) return flowOf(emptyList())
        val user = uid() ?: return flowOf(emptyList())
        return tickets(db().collection(TICKETS).whereEqualTo("uid", user).limit(MAX_TICKETS))
    }

    /** Todos os chamados (só o dono consegue ler, pelas regras do Firestore). */
    fun allTickets(ctx: Context): Flow<List<Ticket>> {
        if (!available(ctx)) return flowOf(emptyList())
        return tickets(db().collection(TICKETS).limit(MAX_TICKETS))
    }

    private fun tickets(query: Query): Flow<List<Ticket>> = callbackFlow {
        val reg = query.addSnapshotListener { snap, error ->
            if (error != null) {
                Log.w(TAG, "chamados", error)
                return@addSnapshotListener
            }
            trySend(snap?.documents?.map { toTicket(it) } ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

    /** Mensagens de um chamado, em ordem de envio (tempo real). */
    fun messages(ctx: Context, ticketId: String): Flow<List<SupportMessage>> {
        if (!available(ctx)) return flowOf(emptyList())
        return callbackFlow {
            val reg = db().collection(TICKETS).document(ticketId).collection(MESSAGES)
                .orderBy("at", Query.Direction.ASCENDING)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        Log.w(TAG, "mensagens", error)
                        return@addSnapshotListener
                    }
                    trySend(
                        snap?.documents?.map { d ->
                            SupportMessage(
                                id = d.id,
                                text = d.getString("text").orEmpty(),
                                fromSupport = d.getBoolean("fromSupport") ?: false,
                                at = d.getLong("at") ?: 0L,
                            )
                        } ?: emptyList(),
                    )
                }
            awaitClose { reg.remove() }
        }
    }

    private fun toTicket(d: DocumentSnapshot): Ticket = Ticket(
        id = d.id,
        uid = d.getString("uid").orEmpty(),
        kind = kindOf(d.getString("kind")),
        subject = d.getString("subject").orEmpty(),
        status = statusOf(d.getString("status")),
        createdAt = d.getLong("createdAt") ?: 0L,
        updatedAt = d.getLong("updatedAt") ?: 0L,
        lastMessage = d.getString("lastMessage").orEmpty(),
        lastFromSupport = d.getBoolean("lastFromSupport") ?: false,
        unreadForUser = d.getBoolean("unreadForUser") ?: false,
        waitingSupport = d.getBoolean("waitingSupport") ?: true,
        device = d.getString("device").orEmpty(),
    )

    private fun kindOf(name: String?): TicketKind =
        TicketKind.entries.firstOrNull { it.name == name } ?: TicketKind.HELP

    private fun statusOf(name: String?): TicketStatus =
        TicketStatus.entries.firstOrNull { it.name == name } ?: TicketStatus.OPEN
}
