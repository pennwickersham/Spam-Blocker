package com.spamblocker.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spamblocker.app.data.BlockRule
import com.spamblocker.app.data.BlockedLog
import com.spamblocker.app.data.DatabaseHelper
import com.spamblocker.app.util.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rebuilt UI. Deliberately plain: three tabs, no navigation library, no
 * view-model layer. All database access goes through Dispatchers.IO — the
 * original read SQLite straight from composition.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SpamBlockerApp()
            }
        }
    }
}

private enum class Tab(val label: String) { STATUS("Status"), RULES("Rules"), LOG("Log") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamBlockerApp() {
    var tab by remember { mutableStateOf(Tab.STATUS) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Spam Blocker") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        icon = {}
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.STATUS -> StatusScreen()
                Tab.RULES -> RulesScreen()
                Tab.LOG -> LogScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- status

@Composable
private fun StatusScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }   // bump to re-check

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { version++ }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { version++ }

    val hasRole = remember(version) { hasCallScreeningRole(context) }
    val hasContacts = remember(version) { granted(context, Manifest.permission.READ_CONTACTS) }
    val hasSms = remember(version) { granted(context, Manifest.permission.RECEIVE_SMS) }
    val hasPhone = remember(version) { granted(context, Manifest.permission.READ_PHONE_STATE) }
    val hasListener = remember(version) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Call blocking does nothing until the screening role is granted. " +
                "Everything else is optional and degrades gracefully.",
            fontSize = 13.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(16.dp))

        StatusRow(
            title = "Call screening role",
            ok = hasRole,
            required = true,
            action = "Grant",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = context.getSystemService(RoleManager::class.java)
                    rm?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                        ?.let { roleLauncher.launch(it) }
                }
            }
        )
        StatusRow("Contacts access", hasContacts, false, "Grant") {
            permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        }
        StatusRow("SMS access", hasSms, false, "Grant") {
            permLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
        }
        StatusRow("Phone state (SIM detection)", hasPhone, false, "Grant") {
            permLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
        }
        StatusRow("Notification access (SMS muting)", hasListener, false, "Open settings") {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("A note on SMS", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "This app is not the default SMS handler, so it cannot stop a text " +
                "from arriving. It dismisses the notification after the fact. " +
                "The message still lands in your inbox.",
            fontSize = 13.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    ok: Boolean,
    required: Boolean,
    action: String,
    onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    if (ok) "Granted" else if (required) "Required — not granted" else "Not granted",
                    fontSize = 12.sp,
                    color = if (ok) Color(0xFF2E7D32) else if (required) Color(0xFFC62828) else Color.Gray
                )
            }
            if (!ok) TextButton(onClick = onClick) { Text(action) }
        }
    }
}

// ---------------------------------------------------------------- rules

@Composable
private fun RulesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<BlockRule>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }

    fun reload() = scope.launch {
        rules = withContext(Dispatchers.IO) { DatabaseHelper.get(context).getRules() }
    }
    LaunchedEffect(Unit) { reload() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(rules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.value, fontWeight = FontWeight.Medium)
                            Text(
                                "${rule.action} · ${rule.type} · ${rule.appliesTo}" +
                                    if (rule.note.isNotEmpty()) " · ${rule.note}" else "",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = rule.isActive,
                            onCheckedChange = { on ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        DatabaseHelper.get(context).setRuleActive(rule.id, on)
                                    }
                                    reload()
                                }
                            }
                        )
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    DatabaseHelper.get(context).deleteRule(rule.id)
                                }
                                reload()
                            }
                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Add rule") }
    }

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { rule ->
                scope.launch {
                    val id = withContext(Dispatchers.IO) {
                        DatabaseHelper.get(context).addRule(rule)
                    }
                    showAdd = false
                    reload()
                    if (id == -1L) {
                        // The original silently swallowed duplicates via
                        // CONFLICT_IGNORE with no feedback at all (defect D11).
                        android.widget.Toast.makeText(
                            context, "That rule already exists", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (BlockRule) -> Unit) {
    var value by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(BlockRule.TYPE_NUMBER) }
    var action by remember { mutableStateOf(BlockRule.ACTION_BLOCK) }
    var typeMenu by remember { mutableStateOf(false) }

    val types = listOf(
        BlockRule.TYPE_NUMBER to "Exact number",
        BlockRule.TYPE_PREFIX to "Starts with (area code)",
        BlockRule.TYPE_KEYWORD to "Message keyword",
        BlockRule.TYPE_REGEX to "Regex",
        BlockRule.TYPE_UNKNOWN to "Withheld caller ID",
        BlockRule.TYPE_SHORT_CODE to "Short codes",
        BlockRule.TYPE_NOT_IN_CONTACTS to "Anyone not in contacts"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add rule") },
        text = {
            Column {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }) {
                        Text(types.first { it.first == type }.second)
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        types.forEach { (t, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { type = t; typeMenu = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (type == BlockRule.TYPE_KEYWORD) "Keyword" else "Number or pattern") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow instead of block", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(
                        checked = action == BlockRule.ACTION_ALLOW,
                        onCheckedChange = {
                            action = if (it) BlockRule.ACTION_ALLOW else BlockRule.ACTION_BLOCK
                        }
                    )
                }
                if (type == BlockRule.TYPE_PREFIX) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Enter digits only, without the country code: 210, not +1210.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank() ||
                    type == BlockRule.TYPE_UNKNOWN ||
                    type == BlockRule.TYPE_NOT_IN_CONTACTS ||
                    type == BlockRule.TYPE_SHORT_CODE,
                onClick = {
                    val scope = when (type) {
                        BlockRule.TYPE_KEYWORD -> BlockRule.SCOPE_SMS
                        BlockRule.TYPE_UNKNOWN -> BlockRule.SCOPE_CALL
                        else -> BlockRule.SCOPE_BOTH
                    }
                    onAdd(
                        BlockRule(
                            type = type,
                            value = value.ifBlank { type.lowercase().replace('_', ' ') },
                            action = action,
                            appliesTo = scope
                        )
                    )
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- log

@Composable
private fun LogScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<BlockedLog>>(emptyList()) }
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    fun reload() = scope.launch {
        logs = withContext(Dispatchers.IO) { DatabaseHelper.get(context).getLogs() }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${logs.size} entries", Modifier.weight(1f), fontSize = 13.sp, color = Color.Gray)
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { DatabaseHelper.get(context).clearLogs() }
                    reload()
                }
            }) { Text("Clear") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(logs, key = { it.id }) { log ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row {
                        Text(
                            PhoneNumbers.display(log.sender),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(fmt.format(Date(log.timestamp)), fontSize = 12.sp, color = Color.Gray)
                    }
                    Text(
                        "${log.type} · ${log.ruleMatched}",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasCallScreeningRole(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val rm = context.getSystemService(RoleManager::class.java) ?: return false
    return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}
