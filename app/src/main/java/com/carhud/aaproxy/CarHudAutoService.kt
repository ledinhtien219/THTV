package com.carhud.aaproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CarHudAutoService : CarAppService() {

    private var powerDisconnectReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        try {
            AppCrashHandler.init(applicationContext)
            CarTtsManager.init(applicationContext)
            CarTtsManager.startListeningToRepository()
            GpsSpeedManager.start(applicationContext)
            WazeHlpWebSocketManager.start()

            // Listen for cable unplug / USB disconnection
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            powerDisconnectReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.i("CarHudAutoService", "Cable unplugged event: ${intent?.action}")
                    context?.let {
                        CarAppShutdownManager.shutdownCompletely(it.applicationContext, "Cable unplugged (${intent?.action})")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(powerDisconnectReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(powerDisconnectReceiver, filter)
            }
        } catch (e: Exception) {
            AppCrashHandler.logError(e, "AndroidAutoServiceOnCreate")
        }
    }

    override fun onDestroy() {
        try {
            powerDisconnectReceiver?.let { unregisterReceiver(it) }
            powerDisconnectReceiver = null
        } catch (e: Exception) {}
        super.onDestroy()
        CarAppShutdownManager.shutdownCompletely(applicationContext, "CarHudAutoService onDestroy (Android Auto Disconnected)")
    }

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return createCarSession()
    }

    override fun onCreateSession(): Session {
        return createCarSession()
    }

    private fun createCarSession(): Session {
        return object : Session() {
            private var currentScreen: CarHudAutoScreen? = null

            init {
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        Log.i("CarHudAutoService", "CarSession onDestroy - keeping background audio active")
                    }
                })
            }

            override fun onCreateScreen(intent: Intent): Screen {
                val ctx: CarContext = carContext
                val screen = CarHudAutoScreen(ctx)
                currentScreen = screen
                return screen
            }

            override fun onCarConfigurationChanged(newConfiguration: Configuration) {
                super.onCarConfigurationChanged(newConfiguration)
                currentScreen?.onCarConfigurationChanged(newConfiguration)
            }
        }
    }
}
