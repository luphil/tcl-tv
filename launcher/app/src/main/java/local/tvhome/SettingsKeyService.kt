package local.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Gives chosen remote buttons a job, system-wide, from config "remoteButtons"
 * (key code -> "settings", "quickPanel" or an app package). E.g. the TCL remote's
 * gear button sends KEYCODE_NOTIFICATION (83), which only Google's disabled
 * homescreen used to handle. Buttons not listed pass through untouched.
 * It reads no screen content.
 */
class SettingsKeyService : AccessibilityService() {
    private var config = Config()
    private var configStamp = -1L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Cheap check on every press; only re-read the file when it changed
        val stamp = Config.lastModified()
        if (stamp != configStamp) {
            configStamp = stamp
            config = Config.load()
        }
        val target = config.remoteButtons[event.keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_UP) open(target)
        return true
    }

    private fun open(target: String) {
        val intent =
            when (target) {
                "settings" -> Intent(Settings.ACTION_SETTINGS)
                "quickPanel" -> Intent().setComponent(ComponentName("com.tcl.dashboard", "com.tcl.dashboard.MainActivity"))
                else -> packageManager.getLeanbackLaunchIntentForPackage(target) ?: packageManager.getLaunchIntentForPackage(target)
            }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            Log.w(TAG, "Nothing to open for $target")
            return
        }
        try {
            startActivity(intent)
        } catch (ex: ActivityNotFoundException) {
            Log.e(TAG, "Can't open $target", ex)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "TVHome"
    }
}
