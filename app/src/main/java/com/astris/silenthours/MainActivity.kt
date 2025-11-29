package com.astris.silenthours

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.astris.callblocker.ui.theme.SilentHoursTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter



class MainActivity : ComponentActivity() {
    private lateinit var themeSettings: ThemeSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themeSettings = ThemeSettings(this)

        setContent {
            val systemInDarkTheme = isSystemInDarkTheme()
            val isThemeSetByUser = themeSettings.isThemeSetByUser
            var isDarkMode by remember {
                mutableStateOf(
                    if (isThemeSetByUser) themeSettings.isDarkTheme
                    else systemInDarkTheme
                )
            }

            SilentHoursTheme(darkTheme = isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        isDarkMode = isDarkMode,
                        onThemeToggle = {
                            val newTheme = !isDarkMode
                            themeSettings.isDarkTheme = newTheme
                            themeSettings.isThemeSetByUser = true
                            // Recreate the activity to apply the new theme
                            recreate()
                        },
                        activity = this
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
    onThemeToggle: () -> Unit,
    activity: Activity
) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings(context) }
    val isBlockingEnabled by appSettings.isBlockingEnabled.collectAsState(initial = true)

    var showAddDialog by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var showSupportSheet by remember { mutableStateOf(false) }
    var blockingRules by remember { mutableStateOf(listOf<BlockingRule>()) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var ruleToEdit by remember { mutableStateOf<BlockingRule?>(null) }
    var isFabMenuOpen by remember { mutableStateOf(false) }


    val database = remember { BlockingRuleDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

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

    fun refreshRules() {
        coroutineScope.launch(Dispatchers.IO) {
            val rulesFromDb = database.blockingRuleDao().getAllRules()

            // Separate individual rules and group rules
            val individualRules = rulesFromDb.filter { it.groupId == null }
            val groupRules = rulesFromDb.filter { it.groupId != null }

            // Process individual rules (one card per contact)
            val individualBlockingRules = individualRules.groupBy { it.phoneNumber }.map { (phoneNumber, rules) ->
                val first = rules.first()
                val distinctTimes = rules.map { it.startTime to it.endTime }.distinct()
                val (startTime, endTime) = if (distinctTimes.size > 1) "Varies" to "" else distinctTimes.first()

                BlockingRule(
                    id = first.id,
                    contactName = first.contactName,
                    phoneNumber = phoneNumber,
                    startTime = startTime,
                    endTime = endTime,
                    daysOfWeek = rules.mapNotNull { it.daysOfWeek.toIntOrNull() }.distinct().sorted(),
                    allowEmergency = first.allowEmergency,
                    isEnabled = first.isEnabled,
                    groupName = null,
                    groupId = null
                )
            }

            // Process group rules (one card per group)
            val groupBlockingRules = groupRules.groupBy { it.groupId }.map { (groupId, rules) ->
                val first = rules.first()
                val allContacts = rules.map { it.contactName }.distinct()
                val allPhoneNumbers = rules.map { it.phoneNumber }.distinct()
                val distinctTimes = rules.map { it.startTime to it.endTime }.distinct()
                val (startTime, endTime) = if (distinctTimes.size > 1) "Varies" to "" else distinctTimes.first()

                BlockingRule(
                    id = first.id,
                    contactName = allContacts.joinToString(", "),
                    phoneNumber = allPhoneNumbers.joinToString(","),
                    startTime = startTime,
                    endTime = endTime,
                    daysOfWeek = rules.mapNotNull { it.daysOfWeek.toIntOrNull() }.distinct().sorted(),
                    allowEmergency = first.allowEmergency,
                    isEnabled = first.isEnabled,
                    groupName = first.groupName,
                    groupId = groupId
                )
            }

            withContext(Dispatchers.Main) {
                blockingRules = individualBlockingRules + groupBlockingRules
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshRules()
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Silent Hours",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    var menuExpanded by remember { mutableStateOf(false) }

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Toggle Theme") },
                                leadingIcon = {
                                    Icon(
                                        if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                        contentDescription = "Toggle Theme"
                                    )
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = isDarkMode,
                                        onCheckedChange = { onThemeToggle() },
                                        thumbContent = {
                                            Icon(
                                                if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize)
                                            )
                                        }
                                    )
                                },
                                onClick = { onThemeToggle() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Support") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Favorite,
                                        contentDescription = "Support",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    showSupportSheet = true
                                    menuExpanded = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete All Rules", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = "Delete All Rules",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showDeleteAllDialog = true
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            val rotationAngle by animateFloatAsState(targetValue = if (isFabMenuOpen) 45f else 0f)

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AnimatedVisibility(visible = isFabMenuOpen) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ElevatedCard(
                            onClick = {
                                showAddGroupDialog = true
                                isFabMenuOpen = false
                            },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Add Group",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                SmallFloatingActionButton(
                                    onClick = {
                                        showAddGroupDialog = true
                                        isFabMenuOpen = false
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Icon(Icons.Default.GroupAdd, "Add Group")
                                }
                            }
                        }
                        ElevatedCard(
                            onClick = {
                                showAddDialog = true
                                isFabMenuOpen = false
                            },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Add Contact",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                SmallFloatingActionButton(
                                    onClick = {
                                        showAddDialog = true
                                        isFabMenuOpen = false
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Icon(Icons.Default.PersonAdd, "Add Contact")
                                }
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { isFabMenuOpen = !isFabMenuOpen },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        Icons.Default.Add,
                        "Add",
                        modifier = Modifier.rotate(rotationAngle)
                    )
                }
            }
        }
    ) { paddingValues ->
        Box {
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

                if (blockingRules.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                                .clickable { showAddDialog = true },
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
                                "Tap + to add a contact",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Enable/Disable Call Blocking",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = isBlockingEnabled,
                                onCheckedChange = { isEnabled ->
                                    coroutineScope.launch {
                                        appSettings.setBlockingEnabled(isEnabled)
                                    }
                                },
                                thumbContent = {
                                    Text(
                                        if (isBlockingEnabled) "ON" else "OFF",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.error,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.errorContainer
                                )
                            )
                        }
                    }

                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Active Rules",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    items(blockingRules, key = { it.id }) { rule ->
                        BlockingRuleCard(
                            rule = rule,
                            isEnabled = isBlockingEnabled,
                            onEdit = { ruleToEdit = rule },
                            onDelete = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (rule.groupId != null) {
                                        val rulesToDelete = database.blockingRuleDao().getRulesByGroupId(rule.groupId)
                                        rulesToDelete.forEach { database.blockingRuleDao().delete(it) }
                                    } else {
                                        val rulesToDelete = database.blockingRuleDao().getRuleByPhoneNumber(rule.phoneNumber)
                                        rulesToDelete.forEach { database.blockingRuleDao().delete(it) }
                                    }
                                    withContext(Dispatchers.Main) {
                                        refreshRules()
                                    }
                                }
                            },
                            onToggle = { toggleRule, enabled ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (toggleRule.groupId != null) {
                                        val rulesToUpdate = database.blockingRuleDao().getRulesByGroupId(toggleRule.groupId)
                                        rulesToUpdate.forEach { ruleEntity ->
                                            database.blockingRuleDao().update(ruleEntity.copy(isEnabled = enabled))
                                        }
                                    } else {
                                        val rulesToUpdate = database.blockingRuleDao().getRuleByPhoneNumber(toggleRule.phoneNumber)
                                        rulesToUpdate.forEach { ruleEntity ->
                                            database.blockingRuleDao().update(ruleEntity.copy(isEnabled = enabled))
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        blockingRules = blockingRules.map {
                                            if (it.id == toggleRule.id) it.copy(isEnabled = enabled) else it
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
            AnimatedVisibility(visible = isFabMenuOpen) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isFabMenuOpen = false
                        },
                    color = Color.Black.copy(alpha = 0.6f)
                ) {}
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Confirm Deletion") },
            text = { Text("Are you sure you want to delete all blocking rules? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            database.blockingRuleDao().deleteAllRules()
                            withContext(Dispatchers.Main) {
                                refreshRules()
                            }
                        }
                        showDeleteAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSupportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSupportSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Support Development",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("UPI") },
                    leadingContent = {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = "UPI"
                        )
                    },
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=9118491505@ptyes"))
                        context.startActivity(intent)
                        showSupportSheet = false
                    }
                )
                ListItem(
                    headlineContent = { Text("Ko-fi") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Coffee,
                            contentDescription = "Ko-fi"
                        )
                    },
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/mriganksharma"))
                        context.startActivity(intent)
                        showSupportSheet = false
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (ruleToEdit != null) {
        ModalBottomSheet(
            onDismissRequest = { ruleToEdit = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            EditRuleContent(
                rule = ruleToEdit!!,
                coroutineScope = coroutineScope,
                isDarkMode = isDarkMode,
                onSave = {
                    ruleToEdit = null
                    refreshRules()
                },
                onCancel = { ruleToEdit = null },
                onDelete = {
                    coroutineScope.launch(Dispatchers.IO) {
                        if (ruleToEdit!!.groupId != null) {
                            val rulesToDelete = database.blockingRuleDao().getRulesByGroupId(ruleToEdit!!.groupId!!)
                            rulesToDelete.forEach { database.blockingRuleDao().delete(it) }
                        } else {
                            val rulesToDelete = database.blockingRuleDao().getRuleByPhoneNumber(ruleToEdit!!.phoneNumber)
                            rulesToDelete.forEach { database.blockingRuleDao().delete(it) }
                        }
                        withContext(Dispatchers.Main) {
                            ruleToEdit = null
                            refreshRules()
                        }
                    }
                }
            )
        }
    }

    if (showAddDialog) {
        AddBlockDialog(
            context = context,
            database = database,
            isDarkMode = isDarkMode,
            onDismiss = { showAddDialog = false},
            onSave = {
                refreshRules()
                showAddDialog = false
            }
        )
    }

    if (showAddGroupDialog) {
        AddGroupDialog(
            context = context,
            database = database,
            isDarkMode = isDarkMode,
            onDismiss = { showAddGroupDialog = false },
            onSave = {
                refreshRules()
                showAddGroupDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGroupDialog(
    context: Context,
    database: BlockingRuleDatabase,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onSave: (BlockingRule) -> Unit
) {
    var selectedContacts by remember { mutableStateOf(listOf<ContactInfo>()) }
    var groupName by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            coroutineScope.launch {
                val contactInfo = getContactFromUri(context, contactUri)
                if (contactInfo != null) {
                    if (!selectedContacts.any { it.phoneNumber == contactInfo.phoneNumber }) {
                        selectedContacts = selectedContacts + contactInfo
                    }
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

    val dayTimes = remember {
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

    var emergencyBypass by remember { mutableStateOf(true) }
    var retryWindow by remember { mutableStateOf("5") }

    Dialog(onDismissRequest = onDismiss) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "New Group",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text("Group Name", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        placeholder = { Text("e.g., Work Colleagues") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(20.dp))

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
                            context = context,
                            isDarkMode = isDarkMode
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

                    AnimatedVisibility(visible = emergencyBypass) {
                        OutlinedTextField(
                            value = retryWindow,
                            onValueChange = { retryWindow = it.filter { char -> char.isDigit() } },
                            label = { Text("Retry Window (minutes)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (selectedContacts.isEmpty() || groupName.isBlank()) return@Button

                            coroutineScope.launch(Dispatchers.IO) {
                                val groupId = "group_${System.currentTimeMillis()}"

                                // Save all contacts with same group ID and schedule
                                selectedContacts.forEach { contact ->
                                    daysState.forEachIndexed { index, day ->
                                        if (day.enabled) {
                                            val times = dayTimes[day.name] ?: Pair("22:00", "07:00")
                                            val startTime = if (day.allDay) "00:00" else times.first
                                            val endTime = if (day.allDay) "23:59" else times.second

                                            database.blockingRuleDao().insert(
                                                BlockingRuleEntity(
                                                    id = "${groupId}_${contact.phoneNumber}_${index}".hashCode(),
                                                    contactName = contact.name,
                                                    phoneNumber = contact.phoneNumber,
                                                    startTime = startTime,
                                                    endTime = endTime,
                                                    daysOfWeek = (index + 1).toString(),
                                                    allowEmergency = emergencyBypass,
                                                    retryWindow = retryWindow.toIntOrNull() ?: 5,
                                                    isEnabled = true,
                                                    createdAt = System.currentTimeMillis(),
                                                    groupName = groupName,
                                                    groupId = groupId
                                                )
                                            )
                                        }
                                    }
                                }

                                // Create a single consolidated rule for display
                                val enabledDays = daysState.withIndex()
                                    .filter { it.value.enabled }
                                    .map { it.index + 1 }

                                if (enabledDays.isNotEmpty()) {
                                    val firstDay = daysState.first { it.enabled }
                                    val times = dayTimes[firstDay.name] ?: Pair("22:00", "07:00")
                                    val startTime = if (firstDay.allDay) "00:00" else times.first
                                    val endTime = if (firstDay.allDay) "23:59" else times.second

                                    val groupRule = BlockingRule(
                                        id = groupId.hashCode(),
                                        contactName = selectedContacts.joinToString(", ") { it.name },
                                        phoneNumber = selectedContacts.joinToString(",") { it.phoneNumber },
                                        startTime = startTime,
                                        endTime = endTime,
                                        daysOfWeek = enabledDays,
                                        allowEmergency = emergencyBypass,
                                        isEnabled = true,
                                        groupName = groupName,
                                        groupId = groupId
                                    )
                                    withContext(Dispatchers.Main) {
                                        onSave(groupRule)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedContacts.isNotEmpty() && groupName.isNotBlank() && daysState.any { it.enabled }
                    ) {
                        Text("Save Group")
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
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var selectedContacts by remember { mutableStateOf(listOf<ContactInfo>()) }
    val coroutineScope = rememberCoroutineScope()

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            coroutineScope.launch {
                val contactInfo = getContactFromUri(context, contactUri)
                if (contactInfo != null) {
                    if (!selectedContacts.any { it.phoneNumber == contactInfo.phoneNumber }) {
                        selectedContacts = selectedContacts + contactInfo
                    }
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

    val dayTimes = remember {
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

    var emergencyBypass by remember { mutableStateOf(true) }
    var retryWindow by remember { mutableStateOf("5") }

    Dialog(onDismissRequest = onDismiss) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "New Block",
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
                            context = context,
                            isDarkMode = isDarkMode
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

                    AnimatedVisibility(visible = emergencyBypass) {
                        OutlinedTextField(
                            value = retryWindow,
                            onValueChange = { retryWindow = it.filter { char -> char.isDigit() } },
                            label = { Text("Retry Window (minutes)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (selectedContacts.isEmpty()) return@Button

                            coroutineScope.launch(Dispatchers.IO) {
                                selectedContacts.forEach { contact ->
                                    daysState.forEachIndexed { index, day ->
                                        if (day.enabled) {
                                            val times = dayTimes[day.name] ?: Pair("22:00", "07:00")
                                            val startTime = if (day.allDay) "00:00" else times.first
                                            val endTime = if (day.allDay) "23:59" else times.second

                                            val ruleId = "${System.currentTimeMillis()}_${contact.phoneNumber}_${index}".hashCode()

                                            database.blockingRuleDao().insert(
                                                BlockingRuleEntity(
                                                    id = ruleId,
                                                    contactName = contact.name,
                                                    phoneNumber = contact.phoneNumber,
                                                    startTime = startTime,
                                                    endTime = endTime,
                                                    daysOfWeek = (index + 1).toString(),
                                                    allowEmergency = emergencyBypass,
                                                    retryWindow = retryWindow.toIntOrNull() ?: 5,
                                                    isEnabled = true,
                                                    createdAt = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    onSave()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedContacts.isNotEmpty() && daysState.any { it.enabled }
                    ) {
                        Text("Save")
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
fun DayBlockingRow(
    day: DayBlockingState,
    onEnableChange: (Boolean) -> Unit,
    onAllDayChange: (Boolean) -> Unit,
    startTime: String = "22:00",
    endTime: String = "07:00",
    onStartTimeChange: (String) -> Unit = {},
    onEndTimeChange: (String) -> Unit = {},
    context: Context,
    isDarkMode: Boolean
) {
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val timeFormatter = remember { mutableStateOf(DateTimeFormatter.ofPattern("HH:mm")) }

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

    if (showStartTimePicker) {
        val theme = if (isDarkMode) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        val initialHour = startTime.split(":")[0].toInt()
        val initialMinute = startTime.split(":")[1].toInt()
        TimePickerDialog(
            context,
            theme,
            { _, hour, minute ->
                val selectedTime = LocalTime.of(hour, minute)
                onStartTimeChange(timeFormatter.value.format(selectedTime))
                showStartTimePicker = false
            },
            initialHour,
            initialMinute,
            false
        ).show()
    }

    if (showEndTimePicker) {
        val theme = if (isDarkMode) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        val initialHour = endTime.split(":")[0].toInt()
        val initialMinute = endTime.split(":")[1].toInt()
        TimePickerDialog(
            context,
            theme,
            { _, hour, minute ->
                val selectedTime = LocalTime.of(hour, minute)
                onEndTimeChange(timeFormatter.value.format(selectedTime))
                showEndTimePicker = false
            },
            initialHour,
            initialMinute,
            false
        ).show()
    }
}

@Composable
fun BlockingRuleCard(
    rule: BlockingRule,
    isEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (BlockingRule, Boolean) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.alpha(if (isEnabled) 1f else 0.5f)
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isEnabled, onClick = onEdit)
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (rule.groupId != null && rule.groupName != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("👥", style = MaterialTheme.typography.titleMedium)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            rule.groupName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        rule.contactName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        rule.contactName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Switch(
                                checked = rule.isEnabled,
                                onCheckedChange = { enabled ->
                                    HapticFeedback.performClick(context)
                                    onToggle(rule, enabled)
                                },
                                enabled = isEnabled
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (rule.startTime == "Varies") {
                                    AssistChip(
                                        onClick = { },
                                        label = { Text("Multiple Times") },
                                        leadingIcon = { Text("⏰") }
                                    )
                                } else if (rule.startTime == "00:00" && rule.endTime == "23:59") {
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
                            IconButton(onClick = onDelete, enabled = isEnabled) {
                                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (rule.allowEmergency) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Emergency bypass enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
    }
}

@Composable
fun EditRuleContent(
    rule: BlockingRule,
    coroutineScope: CoroutineScope,
    isDarkMode: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val database = remember { BlockingRuleDatabase.getDatabase(context) }
    val daysState = remember {
        mutableStateListOf(
            DayBlockingState("Mon", rule.daysOfWeek.contains(1), false),
            DayBlockingState("Tue", rule.daysOfWeek.contains(2), false),
            DayBlockingState("Wed", rule.daysOfWeek.contains(3), false),
            DayBlockingState("Thu", rule.daysOfWeek.contains(4), false),
            DayBlockingState("Fri", rule.daysOfWeek.contains(5), false),
            DayBlockingState("Sat", rule.daysOfWeek.contains(6), false),
            DayBlockingState("Sun", rule.daysOfWeek.contains(7), false)
        )
    }

    val dayTimes = remember {
        mutableStateMapOf<String, Pair<String, String>>()
    }

    LaunchedEffect(rule) {
        coroutineScope.launch(Dispatchers.IO) {
            val rulesFromDb = if (rule.groupId != null) {
                database.blockingRuleDao().getRulesByGroupId(rule.groupId)
            } else {
                database.blockingRuleDao().getRuleByPhoneNumber(rule.phoneNumber)
            }

            val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

            // Initialize all days with the display time as a fallback
            dayNames.forEach { dayName ->
                dayTimes[dayName] = Pair(rule.startTime, rule.endTime)
            }

            // Overwrite with specific times from DB
            rulesFromDb.forEach { entity ->
                val dayIndex = entity.daysOfWeek.toIntOrNull()
                if (dayIndex != null && dayIndex in 1..7) {
                    val dayName = dayNames[dayIndex - 1]
                    val allDay = entity.startTime == "00:00" && entity.endTime == "23:59"
                    withContext(Dispatchers.Main) {
                        dayTimes[dayName] = Pair(entity.startTime, entity.endTime)
                        daysState[dayIndex-1] = daysState[dayIndex-1].copy(allDay = allDay)
                    }
                }
            }
        }
    }


    var emergencyBypass by remember { mutableStateOf(rule.allowEmergency) }
    var retryWindow by remember { mutableStateOf((rule.retryWindow ?: 5).toString()) }


    Column(modifier = Modifier.padding(16.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
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
                    context = context,
                    isDarkMode = isDarkMode
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

            AnimatedVisibility(visible = emergencyBypass) {
                OutlinedTextField(
                    value = retryWindow,
                    onValueChange = { retryWindow = it.filter { char -> char.isDigit() } },
                    label = { Text("Retry Window (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        if (rule.groupId != null) {
                            val oldRules = database.blockingRuleDao().getRulesByGroupId(rule.groupId)
                            oldRules.forEach { database.blockingRuleDao().delete(it) }
                        } else {
                            val oldRules = database.blockingRuleDao().getRuleByPhoneNumber(rule.phoneNumber)
                            oldRules.forEach { database.blockingRuleDao().delete(it) }
                        }

                        daysState.forEachIndexed { index, day ->
                            if (day.enabled) {
                                val times = dayTimes[day.name] ?: Pair("22:00", "07:00")
                                val startTime = if (day.allDay) "00:00" else times.first
                                val endTime = if (day.allDay) "23:59" else times.second

                                val ruleId = "${System.currentTimeMillis()}_${rule.phoneNumber}_${index}".hashCode()

                                database.blockingRuleDao().insert(
                                    BlockingRuleEntity(
                                        id = ruleId,
                                        contactName = rule.contactName,
                                        phoneNumber = rule.phoneNumber,
                                        startTime = startTime,
                                        endTime = endTime,
                                        daysOfWeek = (index + 1).toString(),
                                        allowEmergency = emergencyBypass,
                                        retryWindow = retryWindow.toIntOrNull() ?: 5,
                                        isEnabled = rule.isEnabled,
                                        createdAt = System.currentTimeMillis(),
                                        groupName = rule.groupName,
                                        groupId = rule.groupId
                                    )
                                )
                            }
                        }
                        withContext(Dispatchers.Main) {
                            onSave()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Delete")
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
    val groupName: String? = null,
    val groupId: String? = null,
    val retryWindow: Int? = 5
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

suspend fun getContactFromUri(context: Context, contactUri: Uri): ContactInfo? = withContext(Dispatchers.IO) {
    try {
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
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }

    fun performHeavyClick(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20)
        }
    }
}
