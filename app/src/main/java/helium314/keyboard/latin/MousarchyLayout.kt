// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.IBinder
import android.view.InputDevice
import kotlin.system.exitProcess

/**
 * Run by the Mousarchy server as the `shell` user (`CLASSPATH=<apk> app_process / helium314.keyboard.latin.MousarchyLayout
 * <device> <layout>`): sets the Android layout of its keyboard to the one of the PC (`<layout>` as in
 * `keyboard_layout_<layout>` of com.android.inputdevices), so nobody has to choose it in the settings. `shell` has
 * SET_KEYBOARD_LAYOUT; the input manager is reached by reflection, as its methods are hidden.
 */
object MousarchyLayout {
    private const val LAYOUTS = "com.android.inputdevices/com.android.inputdevices.InputDeviceReceiver/keyboard_layout_"

    @JvmStatic
    fun main(args: Array<String>) {
        val (deviceName, layout) = args
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "input") as IBinder
        val inputManager = Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val type = inputManager.javaClass
        // the keyboard was just created: give the input manager a moment to know it
        repeat(30) {
            val ids = type.getMethod("getInputDeviceIds").invoke(inputManager) as IntArray
            val device = ids.asSequence()
                .mapNotNull { type.getMethod("getInputDevice", Int::class.java).invoke(inputManager, it) as InputDevice? }
                .firstOrNull { it.name == deviceName }
            if (device != null) {
                val identifier = device.javaClass.getMethod("getIdentifier").invoke(device)!!
                type.getMethod("setKeyboardLayoutOverrideForInputDevice", identifier.javaClass, String::class.java)
                    .invoke(inputManager, identifier, LAYOUTS + layout)
                println("$deviceName: $layout")
                exitProcess(0)
            }
            Thread.sleep(100)
        }
        System.err.println("$deviceName not found")
        exitProcess(1)
    }
}
