// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Mousarchy, the mouse of the PC on the phone. Keyarchy only starts its server (`libmousarchy.so`, Rust) as the
 * `shell` user, which may create a mouse in /dev/uhid: it pairs once with the wireless debugging of the phone itself
 * and then reaches it over 127.0.0.1. The PC finds the server on the home network and talks to it with a key agreed
 * by a code of 6 digits that the PC shows and is typed in the settings.
 */
object Mousarchy {
    private const val TAG = "Mousarchy"
    private const val PORT = 27184
    private const val SETTINGS = "com.android.settings"

    private val worker = Executors.newSingleThreadExecutor()
    private var watching = false
    private var lastPairing: Pair<Int, String>? = null

    /** What the last attempt said, for the settings screen. */
    @Volatile var status: String = ""
        private set

    private fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libmousarchy.so")

    fun available(context: Context) = binary(context).exists()

    /**
     * Runs [command] of the binary with the folder of its ADB key and [args]; the result is whether it worked and the
     * last line it wrote.
     */
    private fun run(context: Context, command: String, vararg args: String): Pair<Boolean, String> = try {
        val dir = File(context.filesDir, "mousarchy").apply { mkdirs() }
        val process = ProcessBuilder(listOf(binary(context).path, command, dir.path) + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim().lines().lastOrNull().orEmpty()
        if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroy()
        (process.exitValue() == 0) to output
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }.also { (ok, output) -> Log.i(TAG, "$command: $ok $output") }

    /**
     * Starts the server if it is not running; with [pcCode] it is restarted to take the code of the PC.
     * [then] runs on the main thread.
     */
    fun start(context: Context, pcCode: String? = null, then: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!available(context)) return
        val app = context.applicationContext
        worker.execute {
            val (ok, output) = if (pcCode == null) run(app, "arrancar")
                else run(app, "arrancar", "--emparelhar", pcCode)
            status = output
            Handler(Looper.getMainLooper()).post { then(ok, output) }
        }
    }

    /** Whether the server answers on its port; call it off the main thread. */
    fun running(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 300) }
        true
    } catch (_: Exception) {
        false
    }

    /** Starts the server now and again whenever wireless debugging is turned on (it is off after a reboot). */
    fun watch(context: Context) {
        if (watching || !available(context)) return
        watching = true
        val app = context.applicationContext
        val uri = Settings.Global.getUriFor("adb_wifi_enabled")
        app.contentResolver.registerContentObserver(uri, false, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1) start(app)
            }
        })
        start(app)
    }

    /**
     * Called by the accessibility service when a window of the Settings changes: the dialog of "Pair device with
     * pairing code" has the code (6 digits) and the address of the pairing service (ip:port, or the mDNS host name
     * and port) in the same window, which
     * pairs Keyarchy with the wireless debugging without typing anything.
     */
    fun onSettingsWindow(context: Context, roots: List<AccessibilityNodeInfo>) {
        if (!available(context)) return
        for (root in roots) {
            if (root.packageName != SETTINGS) continue
            val texts = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30) return
                node.text?.toString()?.trim()?.let { texts.add(it) }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
            val code = texts.firstOrNull { it.matches(Regex("\\d{6}")) } ?: continue
            val port = texts.firstNotNullOfOrNull { Regex("^[\\w.-]+:(\\d{2,5})$").find(it)?.groupValues?.get(1)?.toInt() }
                ?: continue
            if (lastPairing == port to code) return
            lastPairing = port to code
            val app = context.applicationContext
            worker.execute {
                val (ok, output) = run(app, "adb-emparelhar", port.toString(), code)
                status = output
                if (ok) start(app)
            }
            return
        }
    }
}
