package com.carhud.aaproxy

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Manages device GPS speed updates for automotive speedometer.
 */
object GpsSpeedManager : LocationListener {

    private const val TAG = "GpsSpeedManager"

    private val _rawGpsSpeed = MutableStateFlow(0)
    val rawGpsSpeed: StateFlow<Int> = _rawGpsSpeed.asStateFlow()

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation.asStateFlow()

    private var locationManager: LocationManager? = null
    private var appContext: Context? = null
    private var isStarted = false

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        appContext = context.applicationContext
        WeatherManager.start(appContext)
        if (isStarted) {
            _lastLocation.value?.let { WeatherManager.updateFromLocation(appContext ?: context, it) }
            return
        }
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = lm

            // Seed initial location immediately from last known location
            val lastGps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
            val lastNet = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
            val bestInitial = when {
                lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                lastGps != null -> lastGps
                else -> lastNet
            }
            if (bestInitial != null && _lastLocation.value == null) {
                _lastLocation.value = bestInitial
                WeatherManager.updateFromLocation(appContext ?: context, bestInitial)
                Log.d(TAG, "Seeded initial location: ${bestInitial.latitude}, ${bestInitial.longitude}")
            }

            val hasGps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val hasNetwork = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (hasGps) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 5f, this, Looper.getMainLooper())
                isStarted = true
                Log.d(TAG, "GPS location updates registered.")
            } else if (hasNetwork) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 10f, this, Looper.getMainLooper())
                isStarted = true
                Log.d(TAG, "Network location updates registered (GPS unavailable).")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GPS listener", e)
        }
    }

    fun stop() {
        try {
            locationManager?.removeUpdates(this)
            isStarted = false
        } catch (e: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        _lastLocation.value = location
        appContext?.let { WeatherManager.updateFromLocation(it, location) }
        if (location.hasSpeed()) {
            val speedKmh = (location.speed * 3.6f).roundToInt().coerceAtLeast(0)
            _rawGpsSpeed.value = speedKmh
            VietmapStateRepository.mergeUpdate(speed = speedKmh, source = "GPS")
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
