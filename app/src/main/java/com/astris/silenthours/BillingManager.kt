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
        billingClient = BillingClient.newBuilder(context)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    handlePurchases(purchases)
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
                Log.d(TAG, "Billing service disconnected")
                // Try to reconnect
                setupBillingClient()
            }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = productDetailsList.firstOrNull()
                Log.d(TAG, "Product details loaded: ${productDetails?.name}")
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    private fun queryPurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.products.contains(PREMIUM_PRODUCT_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {

                // Verify and acknowledge purchase
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }

                // Grant premium
                grantPremium()
                Log.d(TAG, "Premium purchase found and verified")
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged")
            }
        }
    }

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

    suspend fun isPremium(): Boolean {
        return isPremiumFlow.first()
    }

    fun purchasePremium(activity: Activity) {
        val details = productDetails
        if (details == null) {
            Log.e(TAG, "Product details not loaded yet")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val billingResult = billingClient?.launchBillingFlow(activity, billingFlowParams)

        if (billingResult?.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Billing flow launched")
        } else {
            Log.e(TAG, "Failed to launch billing flow: ${billingResult?.debugMessage}")
        }
    }

    fun endConnection() {
        billingClient?.endConnection()
    }
}