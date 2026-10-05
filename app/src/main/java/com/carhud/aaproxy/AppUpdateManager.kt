package com.carhud.aaproxy

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity

/** Keep the existing entry point; use the public release manifest without a GAS redeploy. */
object AppUpdateManager {
    fun checkForUpdate(activity: Activity, force: Boolean = false) {
        (activity as? AppCompatActivity)?.let { UpdateNotificationManager.check(it, manual = force) }
    }
}
