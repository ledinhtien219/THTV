package com.carhud.aaproxy

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Handles complete application shutdown when the cable is unplugged or Android Auto disconnects.
 * Stops all services, audio playback, background tasks, and kills the app process cleanly.
 */
object CarAppShutdownManager {

    private const val TAG = "CarAppShutdownManager"
    const val ACTION_FULL_EXIT = "com.carhud.aaproxy.ACTION_FULL_EXIT"

    @Volatile
    private var isShuttingDown = false

    fun shutdownCompletely(context: Context, reason: String) {
        if (isShuttingDown) return
        isShuttingDown = true
        Log.i(TAG, "Shutting down application completely. Reason: $reason")

        try {
            // 1. Stop Audio Playback and Release WakeLocks
            try {
                CarMediaManager.pausePlayback()
                CarMediaManager.releaseWakeLock()
                CarMediaManager.setCarConnectionState(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping CarMediaManager", e)
            }

            // 2. Stop Background Services & WebSockets
            try {
                WazeHlpWebSocketManager.stop()
                GpsSpeedManager.stop()
                CarTtsManager.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping background tasks", e)
            }

            // 3. Stop MediaBrowserService
            try {
                val mediaIntent = Intent(context, CarMediaBrowserService::class.java)
                context.stopService(mediaIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping CarMediaBrowserService", e)
            }

            // 4. Send Broadcast to finish all active Activities
            try {
                val exitIntent = Intent(ACTION_FULL_EXIT).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(exitIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending full exit broadcast", e)
            }

            // 5. Force process exit after short delay to allow broadcast processing
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.i(TAG, "Killing process PID: ${Process.myPid()}")
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                } catch (e: Exception) {
                    exitProcess(0)
                }
            }, 300L)
        } catch (e: Exception) {
            Log.e(TAG, "Uncaught error during shutdown", e)
            try {
                Process.killProcess(Process.myPid())
                exitProcess(0)
            } catch (ex: Exception) {}
        }
    }
}
