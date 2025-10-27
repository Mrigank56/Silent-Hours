package com.example.silenthours

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.callblocker.ui.theme.SilentHoursTheme
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private lateinit var billingManager: BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        billingManager = BillingManager(this) { isPremium ->
            Log.d("MainActivity", "Premium status: $isPremium")
        }

        setContent {
            var isDarkMode by remember { mutableStateOf(true) }

            SilentHoursTheme(darkTheme = isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        isDarkMode = isDarkMode,
                        onThemeToggle = { isDarkMode = !isDarkMode },
                        billingManager = billingManager,
                        activity = this
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.endConnection()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isDarkMode: Boolean,
    onThemeToggle: () -> Unit,
    billingManager: BillingManager,
    activity: Activity
) {
    val isPremium by billingManager.isPremiumFlow.collectAsState(initial = true)
    var usedSlots by remember { mutableStateOf(0) }
    val freeSlots = 999
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<BlockingRule?>(null) }
    var editingGroupId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var blockingRules by remember { mutableStateOf(listOf<BlockingRule>()) }
    var groupedRules by remember { mutableStateOf(mapOf<String?, List<BlockingRule>>()) }

    val database = remember { BlockingRuleDatabase.getDatabase(context) }

    var hasCallScreeningRole by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            hasCallScreeningRole = roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
        }
    }

    val requestRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            hasCallScreeningRole = roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
        }
    }

    LaunchedEffect(blockingRules) {
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
                isEnabled = entity.isEnabled,
                groupName = entity.groupName,
                groupId = entity.groupId
            )
        }

        usedSlots = blockingRules.count { it.isEnabled }
    }

    LaunchedEffect(blockingRules) {
        // Group rules by groupId or phoneNumber for individual rules
        groupedRules = blockingRules.groupBy { rule ->
            rule.groupId ?: rule.phoneNumber
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Silent Hours",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = { showAddGroupDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Text("👥", style = MaterialTheme.typography.titleLarge)
                }
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(Icons.Filled.Add, "Add Contact", modifier = Modifier.size(28.dp))
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (!hasCallScreeningRole && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "⚠️ Permission Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Enable Call Screening to block calls automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            FilledTonalButton(
                                onClick = {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
                                        val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)
                                        requestRoleLauncher.launch(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("Enable Now")
                            }
                        }
                    }
                }
            }

            if (groupedRules.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No blocking rules yet",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Create rules to block calls during specific times",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            "Tap + to add a contact or 👥 to add a group",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                item {
                    Text(
                        "Active Rules",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Display grouped rules
                items(groupedRules.entries.toList()) { (key, rules) ->
                    val firstRule = rules.first()
                    if (firstRule.groupId != null) {
                        // This is a group
                        GroupBlockingRuleCard(
                            rules = rules,
                            onEdit = {
                                editingGroupId = firstRule.groupId
                            },
                            onDelete = { groupId ->
                                blockingRules = blockingRules.filter { it.groupId != groupId }
                                groupedRules = blockingRules.groupBy { rule ->
                                    rule.groupId ?: rule.phoneNumber
                                }
                                usedSlots = maxOf(0, usedSlots - rules.size)
                            },
                            onToggle = { groupId, enabled ->
                                kotlinx.coroutines.runBlocking {
                                    val rulesToUpdate = database.blockingRuleDao().getRulesByGroupId(groupId)
                                    rulesToUpdate.forEach { ruleEntity ->
                                        database.blockingRuleDao().update(ruleEntity.copy(isEnabled = enabled))
                                    }
                                }
                                blockingRules = blockingRules.map {
                                    if (it.groupId == groupId) it.copy(isEnabled = enabled) else it
                                }
                                groupedRules = blockingRules.groupBy { rule ->
                                    rule.groupId ?: rule.phoneNumber
                                }
                            }
                        )
                    } else {
                        // This is an individual contact
                        BlockingRuleCard(
                            rule = firstRule,
                            onEdit = { editRule -> editingRule = editRule },
                            onDelete = { deleteRule ->
                                blockingRules = blockingRules.filter { it.phoneNumber != deleteRule.phoneNumber }
                                groupedRules = blockingRules.groupBy { rule ->
                                    rule.groupId ?: rule.phoneNumber
                                }
                                usedSlots = maxOf(0, usedSlots - 1)
                            },
                            onToggle = { toggleRule, enabled ->
                                kotlinx.coroutines.runBlocking {
                                    val rulesToUpdate = database.blockingRuleDao().getRuleByPhoneNumber(toggleRule.phoneNumber)
                                    rulesToUpdate.forEach { ruleEntity ->
                                        database.blockingRuleDao().update(ruleEntity.copy(isEnabled = enabled))
                                    }
                                }
                                blockingRules = blockingRules.map {
                                    if (it.phoneNumber == toggleRule.phoneNumber) it.copy(isEnabled = enabled) else it
                                }
                                groupedRules = blockingRules.groupBy { rule ->
                                    rule.groupId ?: rule.phoneNumber
                                }
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
                groupedRules = blockingRules.groupBy { r ->
                    r.groupId ?: r.phoneNumber
                }
                usedSlots++
                showAddDialog = false
            }
        )
    }

    if (showAddGroupDialog) {
        AddGroupDialog(
            context = context,
            database = database,
            onDismiss = { showAddGroupDialog = false },
            onSave = { newRules ->
                blockingRules = blockingRules + newRules
                groupedRules = blockingRules.groupBy { rule ->
                    rule.groupId ?: rule.phoneNumber
                }
                usedSlots += newRules.size
                showAddGroupDialog = false
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
                blockingRules = blockingRules.map { if (it.phoneNumber == rule.phoneNumber) rule else it }
                groupedRules = blockingRules.groupBy { r ->
                    r.groupId ?: r.phoneNumber
                }
                editingRule = null
            }
        )
    }

    if (editingGroupId != null) {
        val groupRules = blockingRules.filter { it.groupId == editingGroupId }
        if (groupRules.isNotEmpty()) {
            AddGroupDialog(
                context = context,
                database = database,
                editingGroupId = editingGroupId,
                editingRules = groupRules,
                onDismiss = { editingGroupId = null },
                onSave = { updatedRules ->
                    blockingRules = blockingRules.filter { it.groupId != editingGroupId } + updatedRules
                    groupedRules = blockingRules.groupBy { rule ->
                        rule.groupId ?: rule.phoneNumber
                    }
                    editingGroupId = null
                }
            )
        }
    }

    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            title = { Text("Upgrade to Premium") },
            text = { Text("Upgrade to Premium for ₹99 (one-time) to add unlimited blocking rules!") },
            confirmButton = {
                Button(onClick = {
                    billingManager.purchasePremium(activity)
                    showUpgradeDialog = false
                }) {
                    Text("Upgrade Now")
                }
            },
            dismissButton = { TextButton(onClick = { showUpgradeDialog = false }) { Text("Maybe Later") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGroupDialog(
    context: Context,
    database: BlockingRuleDatabase,
    editingGroupId: String? = null,
    editingRules: List<BlockingRule>? = null,
    onDismiss: () -> Unit,
    onSave: (List<BlockingRule>) -> Unit
) {
    var selectedContacts by remember { mutableStateOf(listOf<ContactInfo>()) }
    var groupName by remember { mutableStateOf("") }

    LaunchedEffect(editingRules) {
        if (editingRules != null && editingRules.isNotEmpty()) {
            groupName = editingRules.first().groupName ?: ""
            selectedContacts = editingRules.map {
                ContactInfo(it.contactName, it.phoneNumber)
            }.distinctBy { it.phoneNumber }
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            val contactInfo = getContactFromUri(context, contactUri)
            if (contactInfo != null) {
                if (!selectedContacts.any { it.phoneNumber == contactInfo.phoneNumber }) {
                    selectedContacts = selectedContacts + contactInfo
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) contactPickerLauncher.launch(null)
    }

    val daysState = remember {
        if (editingRules != null && editingRules.isNotEmpty()) {
            val daysWithRules = editingRules.map { it.daysOfWeek.first() }
            mutableStateListOf(
                DayBlockingState("Mon", daysWithRules.contains(1), editingRules.find { it.daysOfWeek.contains(1) }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Tue", daysWithRules.contains(2), editingRules.find { it.daysOfWeek.contains(2) }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Wed", daysWithRules.contains(3), editingRules.find { it.daysOfWeek.contains(3) }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Thu", daysWithRules.contains(4), editingRules.find { it.daysOfWeek.contains(4) }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Fri", daysWithRules.contains(5), editingRules.find { it.daysOfWeek.contains(5) }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Sat", daysWithRules.contains(6), editingRules.find { it.daysOfWeek.contains(6) }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false),
                DayBlockingState("Sun", daysWithRules.contains(7), editingRules.find { it.daysOfWeek.contains(7) }?.let { it.startTime == "00:00" && it.endTime == "23:59" } ?: false)
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
        if (editingRules != null && editingRules.isNotEmpty()) {
            mutableStateMapOf(
                "Mon" to (editingRules.find { it.daysOfWeek.contains(1) }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Tue" to (editingRules.find { it.daysOfWeek.contains(2) }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Wed" to (editingRules.find { it.daysOfWeek.contains(3) }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Thu" to (editingRules.find { it.daysOfWeek.contains(4) }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Fri" to (editingRules.find { it.daysOfWeek.contains(5) }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Sat" to (editingRules.find { it.daysOfWeek.contains(6) }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00")),
                "Sun" to (editingRules.find { it.daysOfWeek.contains(7) }?.let { Pair(it.startTime, it.endTime) } ?: Pair("22:00", "07:00"))
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

    var emergencyBypass by remember { mutableStateOf(editingRules?.firstOrNull()?.allowEmergency ?: true) }
    var retryWindow by remember { mutableStateOf(5) }

    Dialog(onDismissRequest = onDismiss) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (editingGroupId != null) "Edit Group" else "New Group",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Group Name") },
                        placeholder = { Text("e.g., Family, Work, Friends") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (selectedContacts.isNotEmpty()) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "${selectedContacts.size} Contacts",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                selectedContacts.forEach { contact ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                contact.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                contact.phoneNumber,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = {
                                            selectedContacts = selectedContacts.filter { it.phoneNumber != contact.phoneNumber }
                                        }) {
                                            Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    if (contact != selectedContacts.last()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

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
                        Icon(Icons.Filled.Person, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Contact")
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

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

                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Emergency Bypass",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Allow if retried within ${retryWindow} min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = emergencyBypass,
                            onCheckedChange = {
                                HapticFeedback.performClick(context)
                                emergencyBypass = it
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (selectedContacts.isEmpty() || groupName.isBlank()) return@Button

                            kotlinx.coroutines.runBlocking {
                                // Delete old rules if editing
                                if (editingGroupId != null) {
                                    val oldRules = database.blockingRuleDao().getRulesByGroupId(editingGroupId)
                                    oldRules.forEach { database.blockingRuleDao().delete(it) }
                                }

                                val newGroupId = editingGroupId ?: "group_${System.currentTimeMillis()}"
                                val allNewRules = mutableListOf<BlockingRule>()

                                selectedContacts.forEach { contact ->
                                    daysState.forEachIndexed { index, day ->
                                        if (day.enabled) {
                                            val times = dayTimes[day.name] ?: Pair("22:00", "07:00")
                                            val startTime = if (day.allDay) "00:00" else times.first
                                            val endTime = if (day.allDay) "23:59" else times.second

                                            val ruleId = "${System.currentTimeMillis()}_${contact.phoneNumber}_${index}".hashCode()

                                            val rule = BlockingRule(
                                                id = ruleId,
                                                contactName = contact.name,
                                                phoneNumber = contact.phoneNumber,
                                                startTime = startTime,
                                                endTime = endTime,
                                                daysOfWeek = listOf(index + 1),
                                                allowEmergency = emergencyBypass,
                                                isEnabled = true,
                                                groupName = groupName,
                                                groupId = newGroupId
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
                                                    createdAt = System.currentTimeMillis(),
                                                    groupName = groupName,
                                                    groupId = newGroupId
                                                )
                                            )

                                            allNewRules.add(rule)
                                        }
                                    }
                                }

                                onSave(allNewRules)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedContacts.isNotEmpty() && daysState.any { it.enabled } && groupName.isNotBlank()
                    ) {
                        Text(if (editingGroupId != null) "Update Group" else "Save Group")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
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
    var selectedContacts by remember { mutableStateOf(listOf<ContactInfo>()) }

    LaunchedEffect(editingRule) {
        if (editingRule != null) {
            selectedContacts = listOf(ContactInfo(editingRule.contactName, editingRule.phoneNumber))
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            val contactInfo = getContactFromUri(context, contactUri)
            if (contactInfo != null) {
                if (!selectedContacts.any { it.phoneNumber == contactInfo.phoneNumber }) {
                    selectedContacts = selectedContacts + contactInfo
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) contactPickerLauncher.launch(null)
    }

    val daysState = remember {
        if (editingRule != null) {
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

    Dialog(onDismissRequest = onDismiss) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (editingRule != null) "Edit Block" else "New Block",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
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
                        Icon(Icons.Filled.Person, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (selectedContacts.isEmpty()) "Select Contact"
                            else selectedContacts.first().name
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

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

                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Emergency Bypass",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Allow if retried within ${retryWindow} min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = emergencyBypass,
                            onCheckedChange = {
                                HapticFeedback.performClick(context)
                                emergencyBypass = it
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (selectedContacts.isEmpty()) return@Button

                            kotlinx.coroutines.runBlocking {
                                if (editingRule != null) {
                                    val oldRules = database.blockingRuleDao().getRuleByPhoneNumber(editingRule.phoneNumber)
                                    oldRules.forEach { database.blockingRuleDao().delete(it) }
                                }

                                val allNewRules = mutableListOf<BlockingRule>()

                                selectedContacts.forEach { contact ->
                                    daysState.forEachIndexed { index, day ->
                                        if (day.enabled) {
                                            val times = dayTimes[day.name] ?: Pair("22:00", "07:00")
                                            val startTime = if (day.allDay) "00:00" else times.first
                                            val endTime = if (day.allDay) "23:59" else times.second

                                            val ruleId = "${System.currentTimeMillis()}_${contact.phoneNumber}_${index}".hashCode()

                                            val rule = BlockingRule(
                                                id = ruleId,
                                                contactName = contact.name,
                                                phoneNumber = contact.phoneNumber,
                                                startTime = startTime,
                                                endTime = endTime,
                                                daysOfWeek = listOf(index + 1),
                                                allowEmergency = emergencyBypass,
                                                isEnabled = true,
                                                null,
                                                null
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

                                            allNewRules.add(rule)
                                        }
                                    }
                                }

                                if (allNewRules.isNotEmpty()) {
                                    val firstContactRules = allNewRules.filter { it.phoneNumber == selectedContacts.first().phoneNumber }
                                    if (firstContactRules.isNotEmpty()) {
                                        val firstRule = firstContactRules.first()
                                        val consolidatedRule = BlockingRule(
                                            id = firstRule.id,
                                            contactName = selectedContacts.first().name,
                                            phoneNumber = firstRule.phoneNumber,
                                            startTime = firstRule.startTime,
                                            endTime = firstRule.endTime,
                                            daysOfWeek = firstContactRules.map { it.daysOfWeek.first() },
                                            allowEmergency = firstRule.allowEmergency,
                                            isEnabled = true,
                                            null,
                                            null
                                        )
                                        onSave(consolidatedRule)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedContacts.isNotEmpty() && daysState.any { it.enabled }
                    ) {
                        Text(if (editingRule != null) "Update" else "Save")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
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
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Select Time",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "${hour.toInt().toString().padStart(2, '0')}:${minute.toInt().toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    "Hour",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

                Text(
                    "Minute",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val timeString = "${hour.toInt().toString().padStart(2, '0')}:${minute.toInt().toString().padStart(2, '0')}"
                            onTimeSelected(timeString)
                            context?.let { ctx -> HapticFeedback.performHeavyClick(ctx) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("OK")
                    }
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

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    day.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(60.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "On",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = day.enabled,
                            onCheckedChange = onEnableChange
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "All Day",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = day.allDay,
                            onCheckedChange = onAllDayChange,
                            enabled = day.enabled
                        )
                    }
                }
            }

            if (day.enabled && !day.allDay) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showStartTimePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(startTime, style = MaterialTheme.typography.labelLarge)
                    }
                    Text("→", modifier = Modifier.align(Alignment.CenterVertically))
                    OutlinedButton(
                        onClick = { showEndTimePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(endTime, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (showStartTimePicker && context != null) {
        TimePickerDialog(
            initialTime = startTime,
            onTimeSelected = { newTime ->
                onStartTimeChange(newTime)
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false },
            context = context
        )
    }

    if (showEndTimePicker && context != null) {
        TimePickerDialog(
            initialTime = endTime,
            onTimeSelected = { newTime ->
                onEndTimeChange(newTime)
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false },
            context = context
        )
    }
}

@Composable
fun BlockingRuleCard(
    rule: BlockingRule,
    onEdit: (BlockingRule) -> Unit,
    onDelete: (BlockingRule) -> Unit,
    onToggle: (BlockingRule, Boolean) -> Unit
) {
    val context = LocalContext.current
    val database = remember { BlockingRuleDatabase.getDatabase(context) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        rule.contactName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Switch(
                        checked = rule.isEnabled,
                        onCheckedChange = { enabled ->
                            HapticFeedback.performClick(context)
                            onToggle(rule, enabled)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rule.startTime == "00:00" && rule.endTime == "23:59") {
                        AssistChip(
                            onClick = { },
                            label = { Text("All Day") },
                            leadingIcon = { Text("⏰") }
                        )
                    } else {
                        AssistChip(
                            onClick = { },
                            label = { Text("${rule.startTime} - ${rule.endTime}") },
                            leadingIcon = { Text("⏰") }
                        )
                    }

                    AssistChip(
                        onClick = { },
                        label = { Text(formatDaysOfWeek(rule.daysOfWeek)) },
                        leadingIcon = { Text("📅") }
                    )
                }

                if (rule.allowEmergency) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Emergency bypass enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onEdit(rule) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Edit")
                    }
                    OutlinedButton(
                        onClick = {
                            kotlinx.coroutines.runBlocking {
                                val rulesToDelete = database.blockingRuleDao().getRuleByPhoneNumber(rule.phoneNumber)
                                rulesToDelete.forEach { ruleEntity ->
                                    database.blockingRuleDao().delete(ruleEntity)
                                }
                            }
                            onDelete(rule)
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
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
    val isEnabled: Boolean,
    val groupName: String?,      // ADD THIS LINE
    val groupId: String?
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