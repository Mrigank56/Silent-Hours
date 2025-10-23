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

                Log.d(TAG, "Current day: $dayOfWeek, Current time: $currentTime")

                // Get all active blocking rules and check each one
                val allRules = database.blockingRuleDao().getAllActiveRules()
                Log.d(TAG, "Total active rules in database: ${allRules.size}")

                // Try to match the phone number
                val normalizedNumber = normalizePhoneNumber(phoneNumber)
                var matchingRules = listOf<BlockingRuleEntity>()

                for (rule in allRules) {
                    val ruleNumber = normalizePhoneNumber(rule.phoneNumber)
                    Log.d(TAG, "Comparing incoming '$normalizedNumber' with rule '$ruleNumber'")

                    if (phoneNumbersMatch(normalizedNumber, ruleNumber)) {
                        matchingRules = matchingRules + rule
                        Log.d(TAG, "MATCH found with ${rule.contactName}")
                    }
                }

                Log.d(TAG, "Found ${matchingRules.size} matching rules")

                for (rule in matchingRules) {
                    Log.d(TAG, "Checking rule: ${rule.contactName}")

                    // Parse days of week from string
                    val ruleDays = rule.daysOfWeek.split(",").mapNotNull { it.toIntOrNull() }
                    Log.d(TAG, "Rule days: $ruleDays")

                    // Check if rule applies today
                    if (!ruleDays.contains(dayOfWeek)) {
                        Log.d(TAG, "Rule doesn't apply today")
                        continue
                    }

                    Log.d(TAG, "Rule applies today!")

                    // Check if we're in the blocking time window
                    val startTime = LocalTime.parse(rule.startTime, DateTimeFormatter.ofPattern("HH:mm"))
                    val endTime = LocalTime.parse(rule.endTime, DateTimeFormatter.ofPattern("HH:mm"))

                    Log.d(TAG, "Time window: ${rule.startTime} - ${rule.endTime}")

                    val inTimeWindow = if (startTime.isBefore(endTime)) {
                        // Normal case: e.g., 9:00 AM to 5:00 PM
                        currentTime.isAfter(startTime) && currentTime.isBefore(endTime)
                    } else {
                        // Overnight case: e.g., 10:00 PM to 7:00 AM
                        currentTime.isAfter(startTime) || currentTime.isBefore(endTime)
                    }

                    Log.d(TAG, "In time window: $inTimeWindow")

                    if (inTimeWindow) {
                        // Check emergency bypass
                        if (rule.allowEmergency) {
                            val retryWindowMillis = rule.retryWindow * 60 * 1000L
                            val lastAttempt = database.callAttemptDao().getLastAttempt(
                                normalizedNumber,
                                System.currentTimeMillis() - retryWindowMillis
                            )

                            if (lastAttempt != null) {
                                Log.d(TAG, "Emergency bypass activated!")
                                return@runBlocking false
                            }
                        }

                        // Record this call attempt
                        database.callAttemptDao().insert(
                            CallAttemptEntity(
                                phoneNumber = normalizedNumber,
                                timestamp = System.currentTimeMillis(),
                                wasBlocked = true
                            )
                        )

                        Log.d(TAG, "Call will be BLOCKED")
                        return@runBlocking true
                    }
                }

                Log.d(TAG, "No matching rules, call will be ALLOWED")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if call should be blocked", e)
            false
        }
    }

    private fun phoneNumbersMatch(number1: String, number2: String): Boolean {
        // Remove all non-digit characters except +
        val clean1 = number1.replace(Regex("[^+\\d]"), "")
        val clean2 = number2.replace(Regex("[^+\\d]"), "")

        // If both have country code, match exactly
        if (clean1.startsWith("+") && clean2.startsWith("+")) {
            return clean1 == clean2
        }

        // Otherwise, match last 10 digits (handles country code differences)
        val digits1 = clean1.replace("+", "").takeLast(10)
        val digits2 = clean2.replace("+", "").takeLast(10)

        return digits1 == digits2
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