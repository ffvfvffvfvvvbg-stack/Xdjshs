package ai.astra.liveagent

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.net.Uri
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var pendingInstruction = ""
    private var pendingBaseUrl = "https://astra-live-agent-ymuzhh.v2.appdeploy.ai"
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) onSpeechResult?.invoke(spoken)
        }
    }
    private var onSpeechResult: ((String) -> Unit)? = null
    private val statusState = androidx.compose.runtime.mutableStateOf("Ready. Enable Accessibility and start a screen-sharing session.")
    private val confirmationState = androidx.compose.runtime.mutableStateOf("")

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AgentSessionService::class.java).apply {
                putExtra(AgentSessionService.EXTRA_INSTRUCTION, pendingInstruction)
                putExtra(AgentSessionService.EXTRA_BASE_URL, pendingBaseUrl)
                putExtra(AgentSessionService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AgentSessionService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 40)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 40)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AstraHome(
                    onStart = { instruction, url ->
                        pendingInstruction = instruction
                        pendingBaseUrl = url.trim().ifBlank { pendingBaseUrl }
                        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                    },
                    onEnableAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onStop = {
                        startService(Intent(this, AgentSessionService::class.java).setAction(AgentSessionService.ACTION_STOP))
                    },
                    onContinue = {
                        startService(Intent(this, AgentSessionService::class.java).setAction(AgentSessionService.ACTION_CONFIRM))
                    },
                    statusState = statusState,
                    confirmationState = confirmationState,
                    onSpeak = { callback ->
                        onSpeechResult = callback
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Astra what to do")
                        }
                        try { speechLauncher.launch(intent) } catch (_: Exception) { callback("Voice recognition is unavailable on this device.") }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(AgentSessionService.ACTION_STATUS)
            addAction(AgentSessionService.ACTION_CONFIRMATION)
        }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(AgentSessionService.EXTRA_MESSAGE) ?: return
            statusState.value = message
            if (intent.action == AgentSessionService.ACTION_CONFIRMATION) confirmationState.value = message
        }
    }

}

@Composable
private fun AstraHome(
    onStart: (String, String) -> Unit,
    onEnableAccessibility: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    statusState: androidx.compose.runtime.MutableState<String>,
    confirmationState: androidx.compose.runtime.MutableState<String>,
    onSpeak: ((String) -> Unit) -> Unit
) {
    var task by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("https://astra-live-agent-ymuzhh.v2.appdeploy.ai") }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ASTRA", style = MaterialTheme.typography.headlineLarge)
        Text("Your general-purpose Android AI assistant")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Enable Accessibility")
                Text("Astra uses Android's visible accessibility actions to operate the apps you choose.")
                Button(onClick = onEnableAccessibility, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Accessibility settings")
                }
            }
        }
        OutlinedTextField(
            value = endpoint, onValueChange = { endpoint = it },
            label = { Text("AppDeploy backend URL") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = task, onValueChange = { task = it },
            label = { Text("What should Astra do?") },
            placeholder = { Text("Open an app, find something, help me complete a task…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSpeak { task = it } }, modifier = Modifier.weight(1f)) {
                Text("Speak")
            }
            Button(
                onClick = {
                    if (task.isBlank()) statusState.value = "Enter or speak a task first."
                    else if (AstraAccessibilityService.instance == null) statusState.value = "Enable Astra in Accessibility settings first."
                    else onStart(task, endpoint)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Start live task") }
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop session") }
        if (confirmationState.value.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Astra needs your approval")
                    Text(confirmationState.value)
                    Text("Review the phone screen yourself before continuing. Do not share PINs or OTPs.")
                    Button(onClick = { confirmationState.value = ""; onContinue() }) { Text("I reviewed the screen — continue") }
                }
            }
        }
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Session status")
                Text(statusState.value)
            }
        }
        Text("Privacy: screen images are sent to your AppDeploy backend for AI planning. Do not start screen sharing on banking, password, PIN or OTP screens. Astra will pause when the AI requests confirmation.")
    }

}
