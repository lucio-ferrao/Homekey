// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Accessibility service of the launcher mode: when a chat is opened from it, taps the message box so the keyboard
 * stays; taps the send button on Enter in apps that ignore Enter; reads the name of a chat the keyboard opens in, to
 * move that person forward in the list; tells the launcher mode about clicks in the app below, to leave it; and keeps
 * the app last in front, for the hide key of the home screen to go back to it. (The
 * name is kept from the screen
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

    /**
     * The name at the top of the chat open in [packageName], if it is in the foreground: WhatsApp's view with the name
     * of the chat, else (MatriX, Element X, made with Compose and without view ids) the heading in the top third of
     * the window, which is the room name with the avatar (and an emoji of status) merged into it.
     */
    private fun chatTitle(packageName: String): String? {
        val root = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
            ?: rootInActiveWindow ?: return null
        if (root.packageName?.toString() != packageName) return null
        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name").firstOrNull()
            ?.text?.toString()?.let { return it }
        val window = Rect().also { root.getBoundsInScreen(it) }
        fun visit(node: AccessibilityNodeInfo, depth: Int): String? {
            if (depth > 40) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading && node.isVisibleToUser && Rect().also { node.getBoundsInScreen(it) }.top < window.top + window.height() / 3)
                (node.text ?: node.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> visit(child, depth + 1)?.let { return it } }
            return null
        }
        return visit(root, 0)
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.packageName != packageName)
            onTapInOtherApp?.invoke()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            rememberForegroundApp(event)
            if (event.packageName == "com.android.settings") Mousarchy.onSettingsWindow(this, windows.mapNotNull { it.root })
        }
    }

    /**
     * Keeps the app of a window of an application that came to the front, for the hide key of the home screen to go
     * back to it; not Keyarchy itself, the system UI, a home app or a dialog of permissions.
     */
    private fun rememberForegroundApp(event: AccessibilityEvent) {
        val app = event.packageName?.toString() ?: return
        if (app == packageName || app in NOT_APPS || app in homeApps) return
        val window = windows.firstOrNull { it.id == event.windowId }
        if (if (window != null) window.type != AccessibilityWindowInfo.TYPE_APPLICATION else !event.isFullScreen) return
        lastApp = app
    }

    /** the home apps, which are not an app to go back to */
    private val homeApps by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
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

        /**
         * When the keyboard opens in the message box of a chat of [packageName]: reads the name of the chat once the
         * window is drawn (a few tries) and moves that person forward in the list of the launcher mode
         */
        fun bumpOpenChatSoon(packageName: String) {
            val context = instance ?: return
            val handler = Handler(Looper.getMainLooper())
            var tries = 0
            val attempt = object : Runnable {
                override fun run() {
                    val title = instance?.chatTitle(packageName)
                    if (title != null) AppLauncherMode.bumpChat(context, title)
                    else if (++tries < 3) handler.postDelayed(this, 400)
                }
            }
            handler.postDelayed(attempt, 300)
        }

        private val NOT_APPS = setOf("android", "com.android.systemui", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller")

        /** the app whose window came last to the front (see [rememberForegroundApp]), null if none since it started */
        var lastApp: String? = null
            private set

        /** called on a click in another app, set while the launcher mode is open (to leave it) */
        var onTapInOtherApp: (() -> Unit)? = null

        /** the running service, or null if it is not enabled in the accessibility settings */
        var instance: ScreenAgentService? = null
            private set
    }
}
