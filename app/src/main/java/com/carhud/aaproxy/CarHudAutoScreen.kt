package com.carhud.aaproxy

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CarHudAutoScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var carSurface: Surface? = null
    private val handler = Handler(Looper.getMainLooper())

    private val nativeSearchListener: (String) -> Unit = { query ->
        handler.post {
            try {
                screenManager.push(CarSearchScreen(carContext, query))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    init {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
            val navManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
            navManager.setNavigationManagerCallback(object : androidx.car.app.navigation.NavigationManagerCallback {
                override fun onStopNavigation() {}
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }

        CarMediaManager.registerCarNativeSearchListener(nativeSearchListener)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                CarMediaManager.unregisterCarNativeSearchListener(nativeSearchListener)
                fullCleanup()
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        carSurface = surfaceContainer.surface
        val width = if (surfaceContainer.width > 0) surfaceContainer.width else 800
        val height = if (surfaceContainer.height > 0) surfaceContainer.height else 480
        val dpi = if (surfaceContainer.dpi > 0) surfaceContainer.dpi else 160

        handler.post {
            setupPresentation(carSurface, width, height, dpi)
            try { invalidate() } catch (e: Exception) {}
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        handler.post {
            try {
                // User switched to Maps/Waze/Android Auto home. Keep the Presentation and
                // VirtualDisplay alive (headless) so Chromium still considers the WebView
                // attached to a visible Window. Detaching/dismissing here makes YouTube/HLS
                // get background-throttled or suspended on a number of Android Auto phones.
                // The display is recreated in setupPresentation() when our surface returns.
                try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}

                val web = CarMediaManager.getPersistentWebView()
                web?.onResume()
                web?.resumeTimers()
                if (CarMediaManager.isPlaying || CarMediaManager.userWantsPlayback) {
                    CarMediaManager.registerService(carContext)
                    CarMediaManager.acquireWakeLock(carContext)
                    CarMediaManager.ensureAudioFocus()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try { invalidate() } catch (e: Exception) {}
        }
    }

    override fun onClick(x: Float, y: Float) {
        handler.post {
            presentation?.dispatchTouch(x, y)
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        handler.post {
            presentation?.dispatchScroll(distanceX, distanceY)
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        handler.post {
            presentation?.dispatchFling(velocityX, velocityY)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {}

    fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        handler.post {
            presentation?.onCarConfigurationChanged(newConfiguration)
        }
    }

    private fun setupPresentation(surface: Surface?, width: Int, height: Int, dpi: Int) {
        if (surface == null || !surface.isValid) return

        try {
            // CRITICAL FIX: NEVER reuse VirtualDisplay if it was headless (surface=null). 
            // Android has a bug where setting surface back causes a permanent black screen (sound but no picture)!
            // Always recreate the VirtualDisplay and Presentation, and just re-attach the persistent WebView.
            presentation?.dismiss()
            virtualDisplay?.release()

            val dm = carContext.getSystemService(CarContext.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay(
                "CarHudYouTubeDisplay",
                width,
                height,
                dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
            virtualDisplay = vd

            val persistentWeb = CarMediaManager.getPersistentCarWebView(carContext)
            val pres = CarPresentation(carContext, vd.display, persistentWeb)
            pres.show()
            presentation = pres

            CarMediaManager.setCarConnectionState(true)
            CarMediaManager.carScreenWidth = width
            CarMediaManager.carScreenHeight = height
            CarMediaManager.carScreenDpi = dpi
            // Respect an explicit user pause. Only keep the media pipeline hot when playback
            // is already active/requested; reopening our screen must not unexpectedly resume audio.
            if (CarMediaManager.isPlaying || CarMediaManager.userWantsPlayback) {
                CarMediaManager.ensureAudioFocus()
                CarMediaManager.acquireWakeLock(carContext)
            }

            try {
                val prefs = carContext.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt("car_screen_width", width)
                    .putInt("car_screen_height", height)
                    .putInt("car_screen_dpi", dpi)
                    .putBoolean("car_screen_connected", true)
                    .apply()
            } catch (e: Exception) {}


        } catch (e: Exception) {
            AppCrashHandler.logError(e, "AndroidAutoSetupPresentation")
            e.printStackTrace()
        }
    }


    private fun fullCleanup() {
        try {
            presentation?.dismiss()
            presentation = null
            virtualDisplay?.release()
            virtualDisplay = null
            CarMediaManager.setCarConnectionState(false)
        } catch (e: Exception) {}
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(Action.BACK)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}




