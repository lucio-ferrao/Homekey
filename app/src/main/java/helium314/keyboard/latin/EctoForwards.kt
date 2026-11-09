// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

/**
 * Asks Ecto for a port forward ("-L 3000") on a server, through its PortForwardService, which only answers Keyarchy (it
 * checks the UID Android stamps on the request). Ecto connects to the server first if needed, which opens it for the
 * fingerprint, so the answer can take a while; [onResult] gets it on the main thread, or a timeout.
 */
object EctoForwards {
    const val ECTO_PACKAGE = "com.mrndtvndv.term"
    private const val SERVICE = "com.mrndtvndv.term.service.PortForwardService"
    // The protocol of Ecto's PortForwardService
    private const val MSG_FORWARD = 1
    private const val KEY_ARGUMENTS = "arguments"
    private const val KEY_SERVER = "server"
    private const val KEY_OK = "ok"
    private const val KEY_MESSAGE = "message"
    // Ecto waits up to 90 s for a connection
    private const val TIMEOUT_MS = 100_000L

    /** "ssh olivais": Ecto opens connected to the saved server named (or with the host) [server] */
    fun connectIntent(server: String): Intent = Intent("com.mrndtvndv.term.action.CONNECT")
        .setComponent(ComponentName(ECTO_PACKAGE, "com.mrndtvndv.term.MainActivity"))
        .putExtra("com.mrndtvndv.term.extra.SERVER", server)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun request(context: Context, server: String?, arguments: String, onResult: (ok: Boolean, text: String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var done = false
        lateinit var connection: ServiceConnection
        fun finish(ok: Boolean, text: String) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            try { context.unbindService(connection) } catch (_: IllegalArgumentException) { }
            onResult(ok, text)
        }
        val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                finish(message.data.getBoolean(KEY_OK), message.data.getString(KEY_MESSAGE).orEmpty())
            }
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val message = Message.obtain(null, MSG_FORWARD).apply {
                    data = Bundle().apply {
                        putString(KEY_ARGUMENTS, arguments)
                        server?.let { putString(KEY_SERVER, it) }
                    }
                    replyTo = replies
                }
                try {
                    Messenger(binder).send(message)
                } catch (_: RemoteException) {
                    finish(false, context.getString(R.string.launcher_ssh_gone))
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = finish(false, context.getString(R.string.launcher_ssh_gone))
        }
        // Ecto may open itself to connect: the visible keyboard lends it the right to start an activity
        val flags = Context.BIND_AUTO_CREATE or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Context.BIND_ALLOW_ACTIVITY_STARTS else 0
        val bound = try {
            context.bindService(Intent().setComponent(ComponentName(ECTO_PACKAGE, SERVICE)), connection, flags)
        } catch (_: SecurityException) {
            false
        }
        if (!bound) return finish(false, context.getString(R.string.launcher_ssh_no_ecto))
        handler.postDelayed({ finish(false, context.getString(R.string.launcher_ssh_timeout)) }, TIMEOUT_MS)
    }
}
