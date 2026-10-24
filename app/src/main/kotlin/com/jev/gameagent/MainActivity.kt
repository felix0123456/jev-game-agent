package com.jev.gameagent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jev.gameagent.input.AccessibilityTouchService
import com.jev.gameagent.overlay.FloatingOverlayService
import com.jev.gameagent.preset.ExecutionPolicy
import com.jev.gameagent.preset.GoalPreset
import com.jev.gameagent.preset.GoalStep
import com.jev.gameagent.preset.PolicyType
import com.jev.gameagent.vision.ScreenCaptureService
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingLaunchPreset: Pair<GoalPreset, ExecutionPolicy>? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                ScreenCaptureService.start(this, result.resultCode, result.data!!)
                FloatingOverlayService.start(this)

                pendingLaunchPreset?.let { (preset, policy) ->
                    JevApplication.instance.coordinator.startPreset(preset, policy)
                    pendingLaunchPreset = null
                }

                Toast.makeText(this, "⚡ Jev Game Autopilot activated! Open your game.", Toast.LENGTH_LONG).show()
                finish() // Minimize to background so user can play
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start agent services", e)
                Toast.makeText(this, "Error starting agent: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission is required for vision.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AgentSetupScreen(
                        onLaunch = { requestScreenCapture(null) },
                        onLaunchPreset = { preset, policy -> requestScreenCapture(Pair(preset, policy)) },
                        onStopServices = {
                            ScreenCaptureService.stopAll(this)
                            Toast.makeText(this, "🛑 Stopped HUD & Perception", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    private fun requestScreenCapture(presetWithPolicy: Pair<GoalPreset, ExecutionPolicy>?) {
        pendingLaunchPreset = presetWithPolicy
        if (ScreenCaptureService.isRunning) {
            // Already capturing, just apply preset and overlay
            presetWithPolicy?.let { (preset, policy) ->
                JevApplication.instance.coordinator.startPreset(preset, policy)
            }
            FloatingOverlayService.start(this)
            Toast.makeText(this, "Started preset on active overlay!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }
}

@Composable
fun AgentSetupScreen(
    onLaunch: () -> Unit,
    onLaunchPreset: (GoalPreset, ExecutionPolicy) -> Unit,
    onStopServices: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = JevApplication.instance
    val repo = app.presetRepository
    val scrollState = rememberScrollState()

    var apiKey by remember { mutableStateOf(app.jevApiClient.apiKey) }
    var endpoint by remember { mutableStateOf(app.jevApiClient.endpoint) }
    var presets by remember { mutableStateOf(repo.getPresets()) }

    var selectedPresetForRun by remember { mutableStateOf<GoalPreset?>(null) }
    var editingPreset by remember { mutableStateOf<GoalPreset?>(null) }
    var isCreatingNewPreset by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    var isServiceRunning by remember { mutableStateOf(ScreenCaptureService.isRunning) }

    val hasOverlayPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    val isAccessibilityEnabled = remember {
        AccessibilityTouchService.instance != null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "⚡ Jev Game Agent",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00E676)
        )

        Text(
            text = "System One Vision & Decision Engine for Android Games",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E3A2F),
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Text(
                text = "● Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                color = Color(0xFF00E676),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // Previous Crash Report Card
        var crashLog by remember { mutableStateOf(JevApplication.getCrashLog(context)) }
        if (crashLog != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF331414)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "⚠️ Previous Crash Report Captured",
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = crashLog!!.take(280) + if (crashLog!!.length > 280) "..." else "",
                        color = Color(0xFFE0E0E0),
                        fontSize = 11.sp,
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Jev Crash Log", crashLog)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Crash log copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy Log", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                JevApplication.clearCrashLog(context)
                                crashLog = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Dismiss", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Active Service Stop Bar (Always visible if running)
        if (isServiceRunning || ScreenCaptureService.isRunning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF421515)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("● Autopilot & HUD Active", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Screen perception is currently running", color = Color.LightGray, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            onStopServices()
                            isServiceRunning = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000))
                    ) {
                        Text("🛑 Stop HUD", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- SEQUENTIAL GOALS & PRESETS SECTION ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎯 Sequential Goal Presets", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { isCreatingNewPreset = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("+ New", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { showImportDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("JSON", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                for (preset in presets) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        "${preset.steps.size} steps • ${preset.defaultPolicy.getSummary()}",
                                        color = Color(0xFF00E676),
                                        fontSize = 11.sp
                                    )
                                }
                                Button(
                                    onClick = { selectedPresetForRun = preset },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Text("▶ Run", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            if (preset.description.isNotBlank()) {
                                Text(preset.description, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Steps: " + preset.steps.joinToString(" ➔ ") { it.name },
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { editingPreset = preset },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Edit Steps", fontSize = 11.sp, color = Color(0xFF81D4FA))
                                }
                                if (presets.size > 1) {
                                    TextButton(
                                        onClick = {
                                            repo.deletePreset(preset.id)
                                            presets = repo.getPresets()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Delete", fontSize = 11.sp, color = Color(0xFFFF5252))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- API SETTINGS SECTION ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Decision Server Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        app.jevApiClient.apiKey = it
                    },
                    label = { Text("OpenJEV Auth Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        app.jevApiClient.endpoint = it
                    },
                    label = { Text("Decision API Endpoint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // --- PERMISSIONS SECTION ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Required Permissions", fontWeight = FontWeight.SemiBold, color = Color.White)

                PermissionRow(
                    title = "Overlay Permission",
                    isGranted = hasOverlayPermission,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    }
                )

                PermissionRow(
                    title = "Accessibility Touch Service",
                    isGranted = isAccessibilityEnabled,
                    onGrant = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )
            }
        }

        // Launch Agent Overlay Button
        Button(
            onClick = onLaunch,
            enabled = hasOverlayPermission && endpoint.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                "🚀 Launch Autopilot HUD",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // --- DIALOG: RUN PRESET (Configuring Loops & Duration) ---
    selectedPresetForRun?.let { preset ->
        var policyType by remember { mutableStateOf(preset.defaultPolicy.type) }
        var repeatCount by remember { mutableStateOf(preset.defaultPolicy.count.toString()) }
        var durationMinutes by remember { mutableStateOf(preset.defaultPolicy.durationMinutes.toString()) }

        AlertDialog(
            onDismissRequest = { selectedPresetForRun = null },
            title = { Text("Run Sequence: ${preset.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select execution mode for this run:", fontSize = 13.sp, color = Color.LightGray)

                    // Option 1: Run Once
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = policyType == PolicyType.RUN_ONCE,
                            onClick = { policyType = PolicyType.RUN_ONCE }
                        )
                        Text("Run Once (1 cycle)", fontSize = 14.sp)
                    }

                    // Option 2: Repeat Count
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = policyType == PolicyType.REPEAT_COUNT,
                            onClick = { policyType = PolicyType.REPEAT_COUNT }
                        )
                        Text("Repeat ", fontSize = 14.sp)
                        OutlinedTextField(
                            value = repeatCount,
                            onValueChange = { repeatCount = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.width(64.dp),
                            singleLine = true
                        )
                        Text(" times", fontSize = 14.sp)
                    }

                    // Option 3: Duration
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = policyType == PolicyType.DURATION_MINUTES,
                            onClick = { policyType = PolicyType.DURATION_MINUTES }
                        )
                        Text("Run for ", fontSize = 14.sp)
                        OutlinedTextField(
                            value = durationMinutes,
                            onValueChange = { durationMinutes = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.width(64.dp),
                            singleLine = true
                        )
                        Text(" minutes", fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = repeatCount.toIntOrNull() ?: 1
                        val minutes = durationMinutes.toIntOrNull() ?: 15
                        val policy = when (policyType) {
                            PolicyType.RUN_ONCE -> ExecutionPolicy(PolicyType.RUN_ONCE)
                            PolicyType.REPEAT_COUNT -> ExecutionPolicy(PolicyType.REPEAT_COUNT, count = count)
                            PolicyType.DURATION_MINUTES -> ExecutionPolicy(PolicyType.DURATION_MINUTES, durationMinutes = minutes)
                        }
                        onLaunchPreset(preset, policy)
                        selectedPresetForRun = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("Start Sequence", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { selectedPresetForRun = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- DIALOG: CREATE / EDIT PRESET ---
    val targetEdit = editingPreset ?: if (isCreatingNewPreset) GoalPreset(name = "Custom Sequence", steps = listOf(GoalStep(name = "Step 1", prompt = "clear active stage"))) else null
    targetEdit?.let { presetToEdit ->
        var pName by remember { mutableStateOf(presetToEdit.name) }
        var pDesc by remember { mutableStateOf(presetToEdit.description) }
        var stepsList by remember { mutableStateOf(presetToEdit.steps) }
        var newStepName by remember { mutableStateOf("") }
        var newStepPrompt by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = {
                editingPreset = null
                isCreatingNewPreset = false
            },
            title = { Text(if (isCreatingNewPreset) "Create Goal Sequence" else "Edit Sequence") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = pName,
                        onValueChange = { pName = it },
                        label = { Text("Sequence Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pDesc,
                        onValueChange = { pDesc = it },
                        label = { Text("Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Steps Sequence (${stepsList.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    for ((idx, step) in stepsList.withIndex()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF262626), RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${idx + 1}. ${step.name}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(step.prompt, color = Color.Gray, fontSize = 10.sp)
                            }
                            IconButton(onClick = {
                                stepsList = stepsList.filterIndexed { i, _ -> i != idx }
                            }) {
                                Text("✕", color = Color(0xFFFF5252), fontSize = 14.sp)
                            }
                        }
                    }

                    // Add Step sub-card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("+ Add Next Step", fontSize = 12.sp, color = Color(0xFF00E676))
                            OutlinedTextField(
                                value = newStepName,
                                onValueChange = { newStepName = it },
                                label = { Text("Step Name (e.g., Claim Chest)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newStepPrompt,
                                onValueChange = { newStepPrompt = it },
                                label = { Text("Objective Prompt for Jev") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    if (newStepName.isNotBlank() && newStepPrompt.isNotBlank()) {
                                        stepsList = stepsList + GoalStep(name = newStepName, prompt = newStepPrompt)
                                        newStepName = ""
                                        newStepPrompt = ""
                                    }
                                },
                                modifier = Modifier.align(Alignment.End),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                            ) {
                                Text("Add Step", color = Color.Black, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pName.isNotBlank() && stepsList.isNotEmpty()) {
                            val updated = presetToEdit.copy(
                                name = pName,
                                description = pDesc,
                                steps = stepsList
                            )
                            repo.savePreset(updated)
                            presets = repo.getPresets()
                        }
                        editingPreset = null
                        isCreatingNewPreset = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("Save Preset", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    editingPreset = null
                    isCreatingNewPreset = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- DIALOG: IMPORT / EXPORT JSON ---
    if (showImportDialog) {
        var jsonText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import / Export Presets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = {
                                val json = repo.exportAllToJson()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Jev Presets", json))
                                Toast.makeText(context, "All presets copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF))
                        ) {
                            Text("Copy All to Clipboard", fontSize = 12.sp)
                        }
                    }

                    Text("Or paste preset JSON below to import:", fontSize = 12.sp, color = Color.Gray)

                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        placeholder = { Text("{\"name\": \"...\", \"steps\": [...]}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (jsonText.isNotBlank()) {
                            val success = repo.importFromJson(jsonText)
                            if (success) {
                                presets = repo.getPresets()
                                Toast.makeText(context, "Presets successfully imported!", Toast.LENGTH_SHORT).show()
                                showImportDialog = false
                            } else {
                                Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("Import", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showImportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun PermissionRow(title: String, isGranted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(
                if (isGranted) "Granted" else "Action Required",
                color = if (isGranted) Color(0xFF00E676) else Color(0xFFFF5252),
                fontSize = 12.sp
            )
        }
        if (!isGranted) {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Enable", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
