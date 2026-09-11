package io.github.jmatts94.ratkingrecon

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

// Deliberately not the *-ktx coroutine extensions of the same names (they
// live in this same package and would otherwise be the ones import
// resolution reaches for) - this class has no CoroutineScope of its own, and
// every Billing call here is already answered from inside a listener/
// callback, not a suspend function.

/**
 * The one real-money product this game sells - see [Shop.PRODUCT_SUPPORTER_PACK].
 *
 * Scoped to whichever screen creates it (only ever [ShopActivity]) rather than
 * held at the Application level: a single non-consumable product does not
 * need a connection kept warm between visits, and closing it in `onDestroy`
 * means a leaked Activity is never the reason Play Billing thinks a purchase
 * flow is still in progress.
 *
 * [onEntitlementRestored] fires for a purchase Play already considers
 * PURCHASED - on [connect] as well as after a fresh buy - so a supporter who
 * reinstalls, or whose purchase was interrupted before this app ever
 * acknowledged it, gets the frame and Scrap back without paying twice. The
 * caller is responsible for making that grant idempotent (see
 * [ShopActivity]'s own guard), since Billing has no idea whether the reward
 * already landed.
 */
class BillingManager(
    context: android.content.Context,
    private val onEntitlementRestored: () -> Unit
) {

    private var productDetails: ProductDetails? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            purchases?.forEach(::handlePurchase)
        }
    }

    // The application Context, not whatever was passed in - the client is
    // built once here and lives as long as this object does, which must
    // never be longer than that, but should never itself be the reason an
    // Activity leaks either.
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    /** The live, localized price to show on the shelf - null until [connect] finishes. */
    var priceText: String? = null
        private set

    fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryProduct()
                restoreExistingPurchases()
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(Shop.PRODUCT_SUPPORTER_PACK)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        client.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val details = productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync
            productDetails = details
            priceText = details.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /**
     * Purchases Play already has on record for this account, PURCHASED or
     * not yet acknowledged - see this class's own doc comment on why a
     * restored entitlement is handled the same way a fresh one is.
     */
    private fun restoreExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.forEach(::handlePurchase)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.contains(Shop.PRODUCT_SUPPORTER_PACK)) return

        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) onEntitlementRestored()
            }
        } else {
            // Already acknowledged - a restore, not a fresh buy. The reward
            // still has to run through the caller's own idempotent guard,
            // since this branch fires on every connect for as long as the
            // product stays non-consumed.
            onEntitlementRestored()
        }
    }

    /** Launches Play's own purchase sheet. Silently does nothing if the product has not loaded yet. */
    fun launchPurchase(activity: Activity) {
        val details = productDetails ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    fun disconnect() {
        client.endConnection()
    }
}
