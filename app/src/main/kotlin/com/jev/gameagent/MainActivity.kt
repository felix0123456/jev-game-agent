package com.jev.gameagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.jev.gameagent.vision.ScreenCaptureService

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            FloatingOverlayService.start(this)
            Toast.makeText(this, "Jev Game Agent activated! Open your game.", Toast.LENGTH_LONG).show()
            finish() // Minimize to background so user can play
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
                        onLaunch = { requestScreenCapture() }
                    )
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }
}

@Composable
fun AgentSetupScreen(onLaunch: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = JevApplication.instance

    var apiKey by remember { mutableStateOf(app.jevApiClient.apiKey) }
    var endpoint by remember { mutableStateOf(app.jevApiClient.endpoint) }

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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

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

        Spacer(modifier = Modifier.height(8.dp))

        // API Key Section
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                app.jevApiClient.apiKey = it
            },
            label = { Text("TypeSafe AI Jev API Key") },
            placeholder = { Text("jev_live_...") },
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

        Spacer(modifier = Modifier.height(12.dp))

        // Permissions Section
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

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onLaunch,
            enabled = apiKey.isNotBlank() && hasOverlayPermission,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                "Start Agent Overlay",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
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
