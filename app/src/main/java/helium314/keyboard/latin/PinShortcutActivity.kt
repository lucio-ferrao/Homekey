// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle

/**
 * Accepts the shortcuts an app asks to add to the home screen ("add to home screen", like a Home Assistant dashboard):
 * once pinned to Homekey they show up in the launcher mode. Other requests (widgets) are left unanswered.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (request != null && request.isValid && request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT)
            request.accept()
        finish()
    }
}
