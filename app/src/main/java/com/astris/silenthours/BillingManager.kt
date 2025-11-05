package com.astris.silenthours

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "premium_prefs")

class BillingManager(
    private val context: Context,
    private val onPremiumStatusChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "BillingManager"
        private const val PREMIUM_PRODUCT_ID = "premium_unlimited_slots"
        private val PREMIUM_KEY = booleanPreferencesKey("is_premium")
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null

    // Flow to observe premium status
    val isPremiumFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PREMIUM_KEY] ?: false
    }

    init {
        setupBillingClient()
    }

    private fun setupBillingClient() {
        if (billingClient != null && billingClient!!.isReady) return

        billingClient = BillingClient.newBuilder(context)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    handlePurchases(purchases)
                } else {
                    Log.e(TAG, "Purchase update failed: ${billingResult.debugMessage}")
                }
            }
            .enablePendingPurchases()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup successful")
                    queryProductDetails()
                    queryPurchases() // Check existing purchases
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected — retrying...")
                reconnect()
            }
        })
    }

    private fun reconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2000)
            setupBillingClient()
        }
    }

// Load Product detailsa
    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PREMIUM_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, detailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                productDetails = detailsList.first()
                Log.d(TAG, "Loaded product details for: ${productDetails?.name}")
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

// Check if already purchased
    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Existing purchases: ${purchases.size}")
                handlePurchases(purchases)
            } else {
                Log.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        purchases.forEach { purchase ->
            if (purchase.products.contains(PREMIUM_PRODUCT_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            ) {
                // Acknowledge and grant premium
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                } else {
                    grantPremium()
                }
                Log.d(TAG, "Purchase handled: ${purchase.products}")
            }
        }
    }

//    Acknowledgment fof the purchase
    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged successfully")
                grantPremium()
            } else {
                Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
            }
        }
    }

//Local save purchase key
    private fun grantPremium() {
        CoroutineScope(Dispatchers.IO).launch {
            context.dataStore.edit { prefs ->
                prefs[PREMIUM_KEY] = true
            }
            withContext(Dispatchers.Main) {
                onPremiumStatusChanged(true)
            }
        }
    }

    /** Public API — returns true if premium already unlocked **/
    suspend fun isPremium(): Boolean = isPremiumFlow.first()

//    LAUCNH gOOOGLE play billing
    fun purchasePremium(activity: Activity) {
        val details = productDetails
        if (details == null) {
            Log.w(TAG, "Product details not loaded yet — retrying query")
            queryProductDetails()
            return
        }

        val paramsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .build()

        val result = billingClient?.launchBillingFlow(activity, billingFlowParams)
        if (result?.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Billing flow launched successfully")
        } else {
            Log.e(TAG, "Failed to launch billing flow: ${result?.debugMessage}")
        }
    }

    fun endConnection() {
        billingClient?.endConnection()
        billingClient = null
    }
}
