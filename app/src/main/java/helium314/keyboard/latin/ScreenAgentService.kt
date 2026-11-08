// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Accessibility service of the launcher mode: when a chat is opened from it, taps the message box so the keyboard
 * stays; taps the send button on Enter in apps that ignore Enter; and tells the launcher mode about clicks in the app
 * below, to leave it. (The name is kept from the screen
 * agent it was part of, so the service stays enabled.)
 */
class ScreenAgentService : AccessibilityService() {
    /**
     * Taps the lowest text field of the app in the foreground, if it is [packageName]: in a chat that is the message
     * box, so the keyboard shows up ready to type. Returns false if the app or the field is not there (yet).
     */
    fun focusMessageField(packageName: String): Boolean {
        val root = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
            ?: rootInActiveWindow ?: return false
        if (root.packageName?.toString() != packageName) return false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (node.isEditable && node.isVisibleToUser && node.isEnabled) fields.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        val field = fields.maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.bottom } ?: return false
        return field.performAction(AccessibilityNodeInfo.ACTION_CLICK) || field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    /** the send button of the app in the foreground, if it is [packageName]: a visible button described as send */
    private fun sendButton(packageName: String): AccessibilityNodeInfo? {
        val root = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
            ?: rootInActiveWindow ?: return null
        if (root.packageName?.toString() != packageName) return null
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (node.isClickable && node.isVisibleToUser && node.isEnabled
                    && SEND.containsMatchIn(node.contentDescription ?: node.text ?: "")) buttons.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return buttons.maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.bottom }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.packageName != packageName)
            onTapInOtherApp?.invoke()
    }

    override fun onInterrupt() {}

    companion object {
        private val SEND = Regex("""(?i)\b(send|enviar)\b""")

        /**
         * Taps the send button of [packageName] (see [sendButton]) a moment later, once the app has the text just
         * typed; false if there is no such button now, or no service.
         */
        fun tapSendButton(packageName: String): Boolean {
            val service = instance ?: return false
            service.sendButton(packageName) ?: return false
            Handler(Looper.getMainLooper()).postDelayed({
                service.sendButton(packageName)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }, 150)
            return true
        }

        /** tries for a few seconds to tap the message box of the chat being opened in [packageName] */
        fun focusMessageFieldSoon(packageName: String) {
            val handler = Handler(Looper.getMainLooper())
            var tries = 0
            val attempt = object : Runnable {
                override fun run() {
                    val service = instance ?: return
                    if (service.focusMessageField(packageName) || ++tries >= 10) return
                    handler.postDelayed(this, 300)
                }
            }
            handler.postDelayed(attempt, 500)
        }

        /** called on a click in another app, set while the launcher mode is open (to leave it) */
        var onTapInOtherApp: (() -> Unit)? = null

        /** the running service, or null if it is not enabled in the accessibility settings */
        var instance: ScreenAgentService? = null
            private set
    }
}
