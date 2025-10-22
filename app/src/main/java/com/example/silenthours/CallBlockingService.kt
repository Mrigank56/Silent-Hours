package com.example.silenthours

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class CallBlockingService : CallScreeningService() {

    companion object {
        private const val TAG = "CallBlockingService"
    }

    private val database by lazy { BlockingRuleDatabase.getDatabase(applicationContext) }

    override fun onScreenCall(callDetails: Call.Details) {
        val incomingNumber = callDetails.handle?.schemeSpecificPart ?: ""
        Log.d(TAG, "========== INCOMING CALL ==========")
        Log.d(TAG, "Raw number: $incomingNumber")
        Log.d(TAG, "Normalized: ${normalizePhoneNumber(incomingNumber)}")

        // Check if this number should be blocked
        val shouldBlock = shouldBlockCall(incomingNumber)

        Log.d(TAG, "Decision: ${if (shouldBlock) "BLOCK" else "ALLOW"}")
        Log.d(TAG, "===================================")

        if (shouldBlock) {
            respondToCall(callDetails, createBlockResponse())
        } else {
            respondToCall(callDetails, createAllowResponse())
        }
    }

    private fun shouldBlockCall(phoneNumber: String): Boolean {
        return try {
            runBlocking {
                val now = LocalDateTime.now()
                val currentTime = LocalTime.now()
                val dayOfWeek = now.dayOfWeek.value // 1=Monday, 7=Sunday

                // Get all blocking rules for this number
                val blockingRules = database.blockingRuleDao().getRuleByPhoneNumber(normalizePhoneNumber(phoneNumber))

                for (rule in blockingRules) {
                    // Parse days of week from string
                    val ruleDays = rule.daysOfWeek.split(",").mapNotNull { it.toIntOrNull() }

                    // Check if rule applies today
                    if (!ruleDays.contains(dayOfWeek)) {
                        continue
                    }

                    // Check if we're in the blocking time window
                    val startTime = LocalTime.parse(rule.startTime, DateTimeFormatter.ofPattern("HH:mm"))
                    val endTime = LocalTime.parse(rule.endTime, DateTimeFormatter.ofPattern("HH:mm"))

                    val inTimeWindow = if (startTime.isBefore(endTime)) {
                        // Normal case: e.g., 9:00 AM to 5:00 PM
                        currentTime.isAfter(startTime) && currentTime.isBefore(endTime)
                    } else {
                        // Overnight case: e.g., 10:00 PM to 7:00 AM
                        currentTime.isAfter(startTime) || currentTime.isBefore(endTime)
                    }

                    if (inTimeWindow) {
                        // Check emergency bypass
                        if (rule.allowEmergency) {
                            val retryWindowMillis = rule.retryWindow * 60 * 1000L
                            val lastAttempt = database.callAttemptDao().getLastAttempt(
                                normalizePhoneNumber(phoneNumber),
                                System.currentTimeMillis() - retryWindowMillis
                            )

                            if (lastAttempt != null) {
                                Log.d(TAG, "Emergency bypass: allowing call from $phoneNumber")
                                return@runBlocking false
                            }
                        }

                        // Record this call attempt
                        database.callAttemptDao().insert(
                            CallAttemptEntity(
                                phoneNumber = normalizePhoneNumber(phoneNumber),
                                timestamp = System.currentTimeMillis(),
                                wasBlocked = true
                            )
                        )
                        return@runBlocking true
                    }
                }

                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if call should be blocked", e)
            false
        }
    }

    private fun normalizePhoneNumber(number: String): String {
        // Remove all non-digit characters except +
        return number.replace(Regex("[^+\\d]"), "")
    }

    private fun createBlockResponse(): CallScreeningService.CallResponse {
        return CallScreeningService.CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipNotification(true)
            .build()
    }

    private fun createAllowResponse(): CallScreeningService.CallResponse {
        return CallScreeningService.CallResponse.Builder()
            .setDisallowCall(false)
            .build()
    }
}