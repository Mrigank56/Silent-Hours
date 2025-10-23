package com.example.silenthours

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkMode by remember { mutableStateOf(true) }

            MaterialTheme(
                colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        isDarkMode = isDarkMode,
                        onThemeToggle = { isDarkMode = !isDarkMode }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isDarkMode: Boolean,
    onThemeToggle: () -> Unit
) {
    var isPremium by remember { mutableStateOf(false) }
    var usedSlots by remember { mutableStateOf(0) }
    val freeSlots = 3
    var showAddDialog by remember { mutableStateOf(false) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<BlockingRule?>(null) }
    val context = LocalContext.current
    var blockingRules by remember { mutableStateOf(listOf<BlockingRule>()) }

    // Initialize database
    val database = remember { BlockingRuleDatabase.getDatabase(context) }

    // Check if app has call screening role
    var hasCallScreeningRole by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            hasCallScreeningRole = roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
        }
    }

    // Request call screening role launcher
    val requestRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            hasCallScreeningRole = roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
        }
    }

    // Load rules from database on first launch
    LaunchedEffect(Unit) {
        val rulesFromDb = database.blockingRuleDao().getAllRules()
        blockingRules = rulesFromDb.map { entity ->
            BlockingRule(
                id = entity.id,
                contactName = entity.contactName,
                phoneNumber = entity.phoneNumber,
                startTime = entity.startTime,
                endTime = entity.endTime,
                daysOfWeek = entity.daysOfWeek.split(",").mapNotNull { it.toIntOrNull() },
                allowEmergency = entity.allowEmergency,
                isEnabled = entity.isEnabled
            )
        }
        usedSlots = blockingRules.count { it.isEnabled }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Guard") },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { onThemeToggle() },
                            thumbContent = {
                                Text(
                                    if (isDarkMode) "🌙" else "☀️",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Warning card if call screening role not granted
            if (!hasCallScreeningRole && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ Call Screening Not Enabled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "This app needs Call Screening permission to block calls. Tap the button below to enable it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
                                    val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)
                                    requestRoleLauncher.launch(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Enable Call Screening")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isPremium) "Premium - Unlimited" else "$usedSlots/$freeSlots free slots",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Block a contact",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!isPremium) {
                            Text(
                                text = "${freeSlots - usedSlots} slots remaining",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (!isPremium && usedSlots >= freeSlots) {
                                showUpgradeDialog = true
                            } else {
                                showAddDialog = true
                            }
                        }
                    ) {
                        Text("Add")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (blockingRules.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Create your first block to automatically silence calls from a contact during specific days or times.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (!isPremium && usedSlots >= freeSlots) {
                                    showUpgradeDialog = true
                                } else {
                                    showAddDialog = true
                                }
                            }
                        ) {
                            Text("Add block")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Select a contact, then choose days or set an entire day/time window to silently block calls.")
                Spacer(modifier = Modifier.height(4.dp))
                Text("• Emergency bypass lets repeat calls within the set window through, if enabled. Turn it off to block no matter what.")
                Spacer(modifier = Modifier.height(4.dp))
                Text("• No ads. 3 slots free. ₹99 unlocks unlimited.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(blockingRules) { rule ->
                        BlockingRuleCard(
                            rule = rule,
                            onEdit = { editRule ->
                                editingRule = editRule
                            },
                            onDelete = { deleteRule ->
                                blockingRules = blockingRules.filter { it.id != deleteRule.id }
                                usedSlots = maxOf(0, usedSlots - 1)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddBlockDialog(
            context = context,
            database = database,
            onDismiss = { showAddDialog = false },
            onSave = { rule ->
                blockingRules = blockingRules + rule
                usedSlots++
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        AddBlockDialog(
            context = context,
            database = database,
            editingRule = editingRule,
            onDismiss = { editingRule = null },
            onSave = { rule ->
                // Update the rule in the list
                blockingRules = blockingRules.map { if (it.phoneNumber == rule.phoneNumber) rule else it }
                editingRule = null
            }
        )
    }

    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            title = { Text("Upgrade to Premium") },
            text = { Text("Upgrade to Premium for ₹99 (one-time) to add unlimited blocking rules!") },
            confirmButton = { Button(onClick = { isPremium = true; showUpgradeDialog = false }) { Text("Upgrade") } },
            dismissButton = { TextButton(onClick = { showUpgradeDialog = false }) { Text("Maybe Later") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlockDialog(
    context: Context,
    database: BlockingRuleDatabase,
    editingRule: BlockingRule? = null,
    onDismiss: () -> Unit,
    onSave: (BlockingRule) -> Unit
) {
    var contactName by remember { mutableStateOf(editingRule?.contactName ?: "") }
    var phoneNumber by remember { mutableStateOf(editingRule?.phoneNumber ?: "") }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            val contactInfo = getContactFromUri(context, contactUri)
            if (contactInfo != null) {
                contactName = contactInfo.name
                phoneNumber = contactInfo.phoneNumber
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) contactPickerLauncher.launch(null)
    }

    // Initialize days state from editing rule if exists
    val daysState = remember {
        if (editingRule != null) {
            // Load all rules for this contact from database to get per-day settings
            val allRulesForContact = runBlocking {
                database.blockingRuleDao().getRuleByPhoneNumber(editingRule.phoneNumber)
            }

            mutableStateListOf(
                DayBlockingState("Mon", allRulesForContact.any { it.daysOfWeek == "1" }, allRulesForContact.find { it.daysOfWeek == "1" }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Tue", allRulesForContact.any { it.daysOfWeek == "2" }, allRulesForContact.find { it.daysOfWeek == "2" }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Wed", allRulesForContact.any { it.daysOfWeek == "3" }, allRulesForContact.find { it.daysOfWeek == "3" }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Thu", allRulesForContact.any { it.daysOfWeek == "4" }, allRulesForContact.find { it.daysOfWeek == "4" }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Fri", allRulesForContact.any { it.daysOfWeek == "5" }, allRulesForContact.find { it.daysOfWeek == "5" }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Sat", allRulesForContact.any { it.daysOfWeek == "6" }, allRulesForContact.find { it.daysOfWeek == "6" }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Sun", allRulesForContact.any { it.daysOfWeek == "7" }, allRulesForContact.find { it.daysOfWeek == "7" }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false)
            )
        } else {
            mutableStateListOf(
                DayBlockingState("Mon", false, false),
                DayBlockingState("Tue", false, false),
                DayBlockingState("Wed", false, false),
                DayBlockingState("Thu", false, false),
                DayBlockingState("Fri", false, false),
                DayBlockingState("Sat", false, false),
                DayBlockingState("Sun", false, false)
            )
        }
    }

    val dayTimes = remember {
        if (editingRule != null) {
            // Load actual times for each day from database
            val allRulesForContact = runBlocking {
                database.blockingRuleDao().getRuleByPhoneNumber(editingRule.phoneNumber)
            }

            mutableStateMapOf(
                "Mon" to (allRulesForContact.find { it.daysOfWeek == "1" }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Tue" to (allRulesForContact.find { it.daysOfWeek == "2" }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Wed" to (allRulesForContact.find { it.daysOfWeek == "3" }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Thu" to (allRulesForContact.find { it.daysOfWeek == "4" }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Fri" to (allRulesForContact.find { it.daysOfWeek == "5" }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Sat" to (allRulesForContact.find { it.daysOfWeek == "6" }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Sun" to (allRulesForContact.find { it.daysOfWeek == "7" }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00"))
            )
        } else {
            mutableStateMapOf(
                "Mon" to Pair("22:00", "07:00"),
                "Tue" to Pair("22:00", "07:00"),
                "Wed" to Pair("22:00", "07:00"),
                "Thu" to Pair("22:00", "07:00"),
                "Fri" to Pair("22:00", "07:00"),
                "Sat" to Pair("22:00", "07:00"),
                "Sun" to Pair("22:00", "07:00")
            )
        }
    }

    var emergencyBypass by remember { mutableStateOf(editingRule?.allowEmergency ?: true) }
    var retryWindow by remember { mutableStateOf(5) }
    var hideNotifications by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (editingRule != null) "Edit block" else "New block", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Select a contact to block during specific days or times.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Contact", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                contactPickerLauncher.launch(null)
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (contactName.isEmpty()) "Select contact" else contactName)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Per-day blocking", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    daysState.forEachIndexed { index, day ->
                        val times = dayTimes[day.name] ?: Pair("22:00", "07:00")
                        DayBlockingRow(
                            day = day,
                            onEnableChange = {
                                HapticFeedback.performClick(context)
                                daysState[index] = day.copy(enabled = it)
                            },
                            onAllDayChange = {
                                HapticFeedback.performClick(context)
                                daysState[index] = day.copy(allDay = it)
                            },
                            startTime = times.first,
                            endTime = times.second,
                            onStartTimeChange = { dayTimes[day.name] = times.copy(first = it) },
                            onEndTimeChange = { dayTimes[day.name] = times.copy(second = it) },
                            context = context
                        )
                        if (index < daysState.size - 1) Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Emergency bypass", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("If caller retries within window, allow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = emergencyBypass, onCheckedChange = {
                            HapticFeedback.performClick(context)
                            emergencyBypass = it
                        })
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Retry window (minutes)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = retryWindow.toString(), onValueChange = { retryWindow = it.toIntOrNull() ?: 5 }, modifier = Modifier.fillMaxWidth(), enabled = emergencyBypass)

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hide notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("No ring/notifications during block.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = hideNotifications, onCheckedChange = {
                            HapticFeedback.performClick(context)
                            hideNotifications = it
                        })
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (contactName.isEmpty()) {
                                // Show error: contact required
                                return@Button
                            }

                            kotlinx.coroutines.runBlocking {
                                // If editing, delete old rules first
                                if (editingRule != null) {
                                    val oldRules = database.blockingRuleDao().getRuleByPhoneNumber(editingRule.phoneNumber)
                                    oldRules.forEach { database.blockingRuleDao().delete(it) }
                                }

                                // Save one rule per enabled day (allows different times per day)
                                val newRules = mutableListOf<BlockingRule>()

                                daysState.forEachIndexed { index, day ->
                                    if (day.enabled) {
                                        val times = dayTimes[day.name] ?: Pair("22:00", "07:00")
                                        val startTime = if (day.allDay) "00:00" else times.first
                                        val endTime = if (day.allDay) "23:59" else times.second

                                        val ruleId = "${System.currentTimeMillis()}_${index}".hashCode()

                                        val rule = BlockingRule(
                                            id = ruleId,
                                            contactName = contactName,
                                            phoneNumber = phoneNumber,
                                            startTime = startTime,
                                            endTime = endTime,
                                            daysOfWeek = listOf(index + 1), // Single day
                                            allowEmergency = emergencyBypass,
                                            isEnabled = true
                                        )

                                        database.blockingRuleDao().insert(
                                            BlockingRuleEntity(
                                                id = rule.id,
                                                contactName = rule.contactName,
                                                phoneNumber = rule.phoneNumber,
                                                startTime = rule.startTime,
                                                endTime = rule.endTime,
                                                daysOfWeek = (index + 1).toString(),
                                                allowEmergency = rule.allowEmergency,
                                                retryWindow = retryWindow,
                                                isEnabled = rule.isEnabled,
                                                createdAt = System.currentTimeMillis()
                                            )
                                        )

                                        newRules.add(rule)
                                    }
                                }

                                // Create a consolidated display rule (shows the first day's time)
                                if (newRules.isNotEmpty()) {
                                    val firstRule = newRules.first()
                                    val consolidatedRule = BlockingRule(
                                        id = firstRule.id,
                                        contactName = firstRule.contactName,
                                        phoneNumber = firstRule.phoneNumber,
                                        startTime = firstRule.startTime,
                                        endTime = firstRule.endTime,
                                        daysOfWeek = newRules.map { it.daysOfWeek.first() },
                                        allowEmergency = firstRule.allowEmergency,
                                        isEnabled = true
                                    )
                                    onSave(consolidatedRule)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (editingRule != null) "Update" else "Save") }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun TimePickerDialog(
    initialTime: String = "22:00",
    onTimeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    context: Context? = null
) {
    val parts = initialTime.split(":")
    val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 22
    val initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0

    var hour by remember { mutableStateOf(initialHour.toFloat()) }
    var minute by remember { mutableStateOf(initialMinute.toFloat()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select Time", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(24.dp))
                Text("${hour.toInt().toString().padStart(2, '0')}:${minute.toInt().toString().padStart(2, '0')}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(24.dp))

                Text("Hour: ${hour.toInt().toString().padStart(2, '0')}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = hour,
                    onValueChange = {
                        hour = it
                        context?.let { ctx -> HapticFeedback.performClick(ctx) }
                    },
                    valueRange = 0f..23f,
                    steps = 23,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Minute: ${minute.toInt().toString().padStart(2, '0')}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = minute,
                    onValueChange = {
                        minute = it
                        context?.let { ctx -> HapticFeedback.performClick(ctx) }
                    },
                    valueRange = 0f..59f,
                    steps = 59,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = {
                            val timeString = "${hour.toInt().toString().padStart(2, '0')}:${minute.toInt().toString().padStart(2, '0')}"
                            onTimeSelected(timeString)
                            context?.let { ctx -> HapticFeedback.performHeavyClick(ctx) }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("OK") }
                }
            }
        }
    }
}

@Composable
fun DayBlockingRow(
    day: DayBlockingState,
    onEnableChange: (Boolean) -> Unit,
    onAllDayChange: (Boolean) -> Unit,
    startTime: String = "22:00",
    endTime: String = "07:00",
    onStartTimeChange: (String) -> Unit = {},
    onEndTimeChange: (String) -> Unit = {},
    context: Context? = null
) {
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(day.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(50.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = day.enabled, onCheckedChange = onEnableChange)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All day", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = day.allDay, onCheckedChange = onAllDayChange, enabled = day.enabled)
                }
            }

            if (day.enabled && !day.allDay) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showStartTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text("From: $startTime")
                    }
                    OutlinedButton(onClick = { showEndTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text("To: $endTime")
                    }
                }
            }
        }
    }

    if (showStartTimePicker && context != null) {
        TimePickerDialog(initialTime = startTime, onTimeSelected = { newTime -> onStartTimeChange(newTime); showStartTimePicker = false }, onDismiss = { showStartTimePicker = false }, context = context)
    }

    if (showEndTimePicker && context != null) {
        TimePickerDialog(initialTime = endTime, onTimeSelected = { newTime -> onEndTimeChange(newTime); showEndTimePicker = false }, onDismiss = { showEndTimePicker = false }, context = context)
    }
}

@Composable
fun BlockingRuleCard(rule: BlockingRule, onEdit: (BlockingRule) -> Unit, onDelete: (BlockingRule) -> Unit) {
    val context = LocalContext.current
    val database = remember { BlockingRuleDatabase.getDatabase(context) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.contactName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Show if this is "All day"
                    if (rule.startTime == "00:00" && rule.endTime == "23:59") {
                        Text("⏰ All day", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("⏰ ${rule.startTime} - ${rule.endTime}", style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("📅 ${formatDaysOfWeek(rule.daysOfWeek)}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(if (rule.allowEmergency) "🚨 Emergency bypass enabled" else "🚫 No emergency bypass", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onEdit(rule) },
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("✏️", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = {
                            // Delete all rules for this contact
                            kotlinx.coroutines.runBlocking {
                                val rulesToDelete = database.blockingRuleDao().getRuleByPhoneNumber(rule.phoneNumber)
                                rulesToDelete.forEach { ruleEntity ->
                                    database.blockingRuleDao().delete(ruleEntity)
                                }
                            }
                            onDelete(rule)
                        },
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text("🗑️", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

fun formatDaysOfWeek(days: List<Int>): String {
    val dayNames = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
    return when {
        days.size == 7 -> "Every day"
        days == listOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == listOf(6, 7) -> "Weekends"
        else -> days.joinToString(", ") { dayNames[it] ?: "" }
    }
}

data class BlockingRule(
    val id: Int,
    val contactName: String,
    val phoneNumber: String,
    val startTime: String,
    val endTime: String,
    val daysOfWeek: List<Int>,
    val allowEmergency: Boolean,
    val isEnabled: Boolean
)

data class DayBlockingState(
    val name: String,
    val enabled: Boolean,
    val allDay: Boolean
)

data class ContactInfo(
    val name: String,
    val phoneNumber: String
)

fun getContactFromUri(context: Context, contactUri: Uri): ContactInfo? {
    return try {
        var name = ""
        var phoneNumber = ""
        var contactId = ""

        val cursor = context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                contactId = it.getString(idIndex) ?: ""
                name = it.getString(nameIndex) ?: "Unknown"
            }
        }

        if (contactId.isNotEmpty()) {
            val phoneCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )

            phoneCursor?.use { pc ->
                if (pc.moveToFirst()) {
                    val phoneIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    phoneNumber = pc.getString(phoneIndex) ?: "No phone"
                }
            }
        }

        if (name.isNotEmpty() && phoneNumber.isNotEmpty()) {
            ContactInfo(name, phoneNumber)
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

object HapticFeedback {
    fun performClick(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
        } else {
            vibrator.vibrate(10)
        }
    }

    fun performHeavyClick(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            vibrator.vibrate(20)
        }
    }
}