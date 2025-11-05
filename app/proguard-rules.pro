############################################################
# 🧾 General Project Rules
############################################################

# Keep line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable

# Kotlin metadata (needed for reflection, coroutines, DataStore, etc.)
-keepclassmembers class kotlin.Metadata { *; }

# Prevent obfuscation of your app’s data classes and sealed classes
-keep class com.astris.silenthours.** { *; }

############################################################
# ☁️ Google Play Billing
############################################################

# Keep all BillingClient-related classes
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**

############################################################
# 💾 Jetpack DataStore
############################################################

# DataStore uses Kotlin coroutines and reflection
-keep class androidx.datastore.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn androidx.datastore.**
-dontwarn kotlinx.coroutines.**

############################################################
# 🧭 AndroidX and Compose (safe defaults)
############################################################

# Compose UI reflection safety
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Lifecycle, ViewModel, Activity, etc.
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# AndroidX Core / KTX extensions
-dontwarn androidx.core.**

