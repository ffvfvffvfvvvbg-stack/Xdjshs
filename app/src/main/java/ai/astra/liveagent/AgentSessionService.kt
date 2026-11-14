package ai.astra.liveagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.graphics.PixelFormat

class AgentSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var instruction: String = ""
    private var baseUrl: String = ""
    private var screenWidth = 0
    private var screenHeight = 0
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONFIRM) {
            val pending = pendingConfirmation
            if (pending == null) {
                broadcastStatus("There is no pending action to approve. Start the task again.")
                return START_NOT_STICKY
            }
            scope.launch {
                val service = AstraAccessibilityService.instance
                if (service == null) {
                    broadcastStatus("Accessibility service is not enabled.")
                    stopSession()
                    return@launch
                }
                val executed = service.perform(pending, screenWidth, screenHeight)
                pendingConfirmation = null
                if (!executed) {
                    broadcastStatus("The approved action could not be performed. Check the screen and try again.")
                    stopSession()
                    return@launch
                }
                running = true
                runLoop()
            }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Preparing screen session"))
        instruction = intent?.getStringExtra(EXTRA_INSTRUCTION).orEmpty()
        baseUrl = intent?.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (instruction.isBlank() || data == null || baseUrl.isBlank()) {
            stopSession()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
            running = true
            scope.launch { runLoop() }
        } catch (e: Exception) {
            broadcastStatus("Could not start screen sharing: ${e.message}")
            stopSession()
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.coerceAtLeast(1)
        screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "AstraScreenCapture", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
        )
    }

    private suspend fun runLoop() {
        if (!running) return
        try {
            var steps = 0
            while (running && steps < 80) {
                delay(900)
                val jpeg = captureJpeg() ?: throw IllegalStateException("Unable to capture the current screen.")
                val plan = AgentApi.plan(baseUrl, instruction, jpeg)
                broadcastStatus(plan.explanation.ifBlank { "Astra selected: ${plan.action}" })
                if (plan.action == "ask_user") {
                    running = false
                    broadcastConfirmation(plan.question.ifBlank { plan.explanation.ifBlank { "Astra needs more information before continuing." } })
                    updateNotification("Waiting for your input")
                    return
                }
                if (plan.requiresConfirmation) {
                    pendingConfirmation = plan
                    running = false
                    broadcastConfirmation(plan.question.ifBlank { plan.explanation.ifBlank { "Review the current screen and approve this action if it is correct." } })
                    updateNotification("Waiting for your approval")
                    return
                }
                if (plan.action == "done") {
                    broadcastStatus("Task completed.")
                    stopSession()
                    return
                }
                val service = AstraAccessibilityService.instance
                if (service == null) {
                    throw IllegalStateException("Enable Astra Accessibility in Android Settings, then start again.")
                }
                val performed = service.perform(plan, screenWidth, screenHeight)
                if (!performed && plan.action != "wait") {
                    throw IllegalStateException("The requested UI action could not be performed. Check the current screen and try again.")
                }
                steps++
                delay(if (plan.action == "wait") 1600 else 650)
            }
            if (steps >= 80) broadcastStatus("Stopped after 80 steps to prevent an endless task.")
            stopSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            broadcastStatus(e.message ?: "Task stopped because an unexpected error occurred.")
            stopSession()
        }
    }

    private fun captureJpeg(): ByteArray? {
        val image = reader?.acquireLatestImage() ?: return null
        image.use {
            val plane = it.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            val output = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 65, output)
            bitmap.recycle()
            cropped.recycle()
            return output.toByteArray()
        }
    }

    private fun notification(message: String): Notification {
        val channelId = "astra_session"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Astra screen session", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Astra is active")
            .setContentText(message)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    private fun broadcastConfirmation(message: String) {
        sendBroadcast(Intent(ACTION_CONFIRMATION).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    private fun stopSession() {
        running = false
        pendingConfirmation = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }

    private var pendingConfirmation: AgentAction? = null

    companion object {
        const val ACTION_STATUS = "ai.astra.liveagent.STATUS"
        const val ACTION_CONFIRMATION = "ai.astra.liveagent.CONFIRMATION"
        const val ACTION_STOP = "ai.astra.liveagent.STOP"
        const val ACTION_CONFIRM = "ai.astra.liveagent.CONTINUE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 4102
    }
}
