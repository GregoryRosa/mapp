package br.com.mapp.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import br.com.mapp.MappConfig
import br.com.mapp.core.billing.Entitlement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Assinatura mensal pela Google Play.
 *
 * **Toda a dependência da Play Billing Library mora neste arquivo** — a decisão de liberar ou
 * bloquear é do `core/billing/AccessRules` (Kotlin puro, com teste). Se a biblioteca mudar de
 * assinatura num upgrade, só este arquivo precisa mexer.
 *
 * Fluxo: conecta → consulta as compras do usuário → grava o resultado em [EntitlementStore]. A
 * consulta roda ao abrir o app, ao voltar para o app e depois de comprar. O Play responde pelo
 * cache local da própria Play Store, então funciona mesmo com internet ruim.
 *
 * Checagem no aparelho (sem servidor) é o padrão para app pequeno: o motorista mal-intencionado
 * ainda consegue burlar com o APK modificado, mas isso não vaza dado de ninguém e o custo de um
 * servidor de validação não se paga no beta.
 */
object PlayBilling {

    private const val TAG = "MappBilling"

    private val _offer = MutableStateFlow<Offer?>(null)

    /** Preço e período lidos do Play Console, para a tela de assinatura. null = ainda não carregou. */
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** Último erro, em português, para a tela de assinatura. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var client: BillingClient? = null
    private var app: Context? = null
    private var connecting = false

    /** Detalhes do produto, guardados para abrir a compra. */
    private var details: ProductDetails? = null

    /** Oferta escolhida (a com teste grátis, se houver). */
    private var offerToken: String? = null
    private var offerIsTrial = false

    /** Liga o cliente e já consulta. Chamado no `MappApp.onCreate`. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        EntitlementStore.load(ctx)
        if (client == null) {
            client = BillingClient.newBuilder(ctx)
                .setListener { result, purchases ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        store(purchases.orEmpty())
                    } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                        _problem.value = null
                    } else {
                        _problem.value = reason(result)
                    }
                }
                // Obrigatório desde a versão 6 da biblioteca.
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
        }
        connect()
    }

    private fun connect() {
        val c = client ?: return
        if (c.isReady) {
            query()
            return
        }
        if (connecting) return
        connecting = true
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _problem.value = null
                    query()
                    loadOffer()
                } else {
                    _problem.value = reason(result)
                    Log.w(TAG, "setup: ${result.responseCode} ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // A religação acontece no próximo refresh() (a cada volta ao app).
                connecting = false
            }
        })
    }

    /**
     * Reconsulta as compras. Seguro chamar sempre que o app volta ao primeiro plano: é rápido e
     * é o que pega a assinatura cancelada ou reativada fora do app.
     */
    fun refresh() {
        val c = client
        if (c == null) {
            app?.let { start(it) }
            return
        }
        if (!c.isReady) {
            connect()
            return
        }
        query()
        // Preço que não carregou na primeira tentativa (internet ruim) tenta de novo aqui.
        if (_offer.value == null) loadOffer()
    }

    private fun query() {
        val c = client ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                store(purchases)
            } else {
                // Falhou: NÃO mexe no cache — é isso que segura a tolerância sem internet.
                Log.w(TAG, "query: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    /** Guarda o que o Play respondeu e confirma (acknowledge) a compra nova. */
    private fun store(purchases: List<Purchase>) {
        val ctx = app ?: return
        val mine = purchases.filter { it.products.contains(MappConfig.SUBSCRIPTION_ID) }
        val valid = mine.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val e = Entitlement(
            active = valid != null,
            checkedAt = System.currentTimeMillis(),
            // O Play não informa "está no teste grátis" na compra; vale a oferta escolhida na hora
            // da assinatura (perde-se ao trocar de aparelho — é só texto da tela).
            trial = valid != null && offerIsTrial,
            canceled = valid != null && !valid.isAutoRenewing,
        )
        EntitlementStore.save(ctx, e)
        // Assinatura não confirmada em 3 dias é estornada pelo Google.
        valid?.takeIf { !it.isAcknowledged }?.let { ack(it) }
    }

    private fun ack(purchase: Purchase) {
        val c = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        c.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "ack: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    /** Carrega preço/período e a oferta a usar na compra. */
    private fun loadOffer() {
        val c = client ?: return
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(MappConfig.SUBSCRIPTION_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        c.queryProductDetailsAsync(params) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _problem.value = reason(result)
                return@queryProductDetailsAsync
            }
            // Play Billing 8: o segundo parâmetro é QueryProductDetailsResult.
            val list = response.productDetailsList
            val pd = list.firstOrNull { it.productId == MappConfig.SUBSCRIPTION_ID }
            if (pd == null) {
                _problem.value = "A assinatura \"${MappConfig.SUBSCRIPTION_ID}\" não foi encontrada na " +
                    "Google Play. Confira o ID no Play Console e se a versão do app está num canal de teste."
                return@queryProductDetailsAsync
            }
            details = pd
            val chosen = pickOffer(pd)
            offerToken = chosen?.token
            offerIsTrial = chosen?.trialLabel != null
            _offer.value = chosen
        }
    }

    /**
     * Escolhe a oferta: a que tem fase grátis (teste) vem primeiro; senão a primeira que o Play
     * devolveu (o Play já manda só as ofertas às quais o usuário tem direito).
     */
    private fun pickOffer(pd: ProductDetails): Offer? {
        val offers = pd.subscriptionOfferDetails ?: return null
        val withTrial = offers.firstOrNull { o ->
            o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        }
        val chosen = withTrial ?: offers.firstOrNull() ?: return null
        val phases = chosen.pricingPhases.pricingPhaseList
        val paid = phases.lastOrNull { it.priceAmountMicros > 0L } ?: phases.lastOrNull()
        val free = phases.firstOrNull { it.priceAmountMicros == 0L }
        return Offer(
            token = chosen.offerToken,
            price = paid?.formattedPrice.orEmpty(),
            period = periodLabel(paid?.billingPeriod),
            trialLabel = free?.billingPeriod?.let { "Teste grátis de ${periodText(it)}" },
            name = pd.name,
        )
    }

    /**
     * Abre a tela de pagamento do Google. Precisa de uma Activity (a do MApp).
     */
    fun buy(activity: Activity) {
        val c = client
        val pd = details
        val token = offerToken
        if (c == null || !c.isReady) {
            _problem.value = "Sem conexão com a Google Play. Abra a Play Store uma vez e tente de novo."
            connect()
            return
        }
        if (pd == null || token == null) {
            _problem.value = "Ainda carregando o preço da assinatura. Tente de novo em alguns segundos."
            loadOffer()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd)
                        .setOfferToken(token)
                        .build(),
                ),
            )
            .build()
        val result = c.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _problem.value = reason(result)
        }
    }

    /** "P1M" → "mês". */
    private fun periodLabel(iso: String?): String = when (iso) {
        null -> ""
        "P1W" -> "semana"
        "P1M" -> "mês"
        "P3M" -> "trimestre"
        "P6M" -> "semestre"
        "P1Y" -> "ano"
        else -> periodText(iso)
    }

    /** "P7D" → "7 dias". */
    private fun periodText(iso: String): String {
        val m = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(iso) ?: return iso
        val (y, mo, w, d) = m.destructured
        return when {
            y.isNotEmpty() -> if (y == "1") "1 ano" else "$y anos"
            mo.isNotEmpty() -> if (mo == "1") "1 mês" else "$mo meses"
            w.isNotEmpty() -> if (w == "1") "7 dias" else "${w.toInt() * 7} dias"
            d.isNotEmpty() -> "$d dias"
            else -> iso
        }
    }

    private fun reason(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "A Google Play deste celular não aceita pagamentos. Confira se há conta Google com forma de pagamento."
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        ->
            "Sem conexão com a Google Play. Confira a internet e tente de novo."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "Assinatura indisponível para esta conta. Se você está no teste fechado, aceite o convite primeiro."
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
            "Você já tem a assinatura ativa. Toque em \"Já assinei\" para recarregar."
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "Erro de configuração da assinatura (ID ou canal de teste). Avise o suporte."
        else -> "Não deu para falar com a Google Play (código ${result.responseCode})."
    }

    /** Preço e teste grátis para a tela de assinatura. */
    data class Offer(
        val token: String,
        val price: String,
        val period: String,
        val trialLabel: String?,
        val name: String,
    )
}
