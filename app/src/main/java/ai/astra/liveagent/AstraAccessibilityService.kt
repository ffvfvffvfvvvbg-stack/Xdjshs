package ai.astra.liveagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AstraAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun perform(action: AgentAction, screenWidth: Int, screenHeight: Int): Boolean {
        if (action.requiresConfirmation || action.action == "ask_user") return false
        return when (action.action) {
            "tap" -> gesture(action.x, action.y, action.x, action.y, screenWidth, screenHeight, 80)
            "swipe" -> gesture(action.x, action.y, action.endX, action.endY, screenWidth, screenHeight, 450)
            "type" -> typeText(action.text)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "launch_app" -> launchApp(action.app)
            "wait", "done" -> true
            else -> false
        }
    }

    private fun gesture(x: Int, y: Int, endX: Int, endY: Int, w: Int, h: Int, duration: Long): Boolean {
        if (w <= 0 || h <= 0) return false
        val path = Path().apply {
            moveTo((x.coerceIn(0, 1000) * w / 1000f), (y.coerceIn(0, 1000) * h / 1000f))
            lineTo((endX.coerceIn(0, 1000) * w / 1000f), (endY.coerceIn(0, 1000) * h / 1000f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun typeText(value: String): Boolean {
        if (value.isEmpty() || value.length > 2000) return false
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        val node = findFocusedEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let(::findFocusedEditable)
            if (found != null) return found
        }
        return null
    }

    private fun launchApp(query: String): Boolean {
        if (query.isBlank()) return false
        val pm = packageManager
        val direct = pm.getLaunchIntentForPackage(query.trim())
        if (direct != null) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(direct)
            return true
        }
        val target = query.trim().lowercase(Locale.ROOT)
        val candidate = pm.getInstalledApplications(0).firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(target)
        } ?: return false
        val launch = pm.getLaunchIntentForPackage(candidate.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }

    companion object {
        @Volatile var instance: AstraAccessibilityService? = null
            private set
    }
}
