package com.techlabroid.location_manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.app.JobIntentService
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationResult
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.view.FlutterCallbackInformation
import io.flutter.view.FlutterMain
import io.flutter.view.FlutterNativeView
import io.flutter.view.FlutterRunArguments
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class LocationUpdatesIntentService : MethodChannel.MethodCallHandler, JobIntentService() {

    private val locationUpdateQueue = ArrayDeque<HashMap<Any, Any>>()
    private val geofenceEventQueue = ArrayDeque<HashMap<Any, Any>>()
    private lateinit var backgroundChannel: MethodChannel
    private lateinit var context: Context

    companion object {
        @JvmStatic
        private val JOB_ID = UUID.randomUUID().mostSignificantBits.toInt()
        @JvmStatic
        private var backgroundFlutterView: FlutterNativeView? = null
        @JvmStatic
        private val serviceStarted = AtomicBoolean(false)

        @JvmStatic
        private lateinit var pluginRegistrantCallback: PluginRegistry.PluginRegistrantCallback

        @JvmStatic
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, LocationUpdatesIntentService::class.java, JOB_ID, work)
        }

        @JvmStatic
        fun setPluginRegistrant(callback: PluginRegistry.PluginRegistrantCallback) {
            pluginRegistrantCallback = callback
        }
    }

    override fun onCreate() {
        super.onCreate()
        startLocatorService(this)
    }

    private fun startLocatorService(context: Context) {
        Log.e(LocationManagerPlugin.TAG, "DispatcherHandle: ${getCallbackDispatcherHandle(context)} LocationUpdateHandle: ${getLocationUpdateHandle(context)} GeoFenceEventHandle: ${getGeoFenceEventHandle(context)}")
        synchronized(serviceStarted) {
            this.context = context
            if (backgroundFlutterView == null) {
                val callbackHandle = getCallbackDispatcherHandle(context)

                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                backgroundFlutterView = FlutterNativeView(context, true)
                val registry = backgroundFlutterView!!.pluginRegistry
                pluginRegistrantCallback.registerWith(registry)
                val args = FlutterRunArguments()
                args.bundlePath = FlutterMain.findAppBundlePath(context)
                args.entrypoint = callbackInfo.callbackName
                args.libraryPath = callbackInfo.callbackLibraryPath
                backgroundFlutterView!!.runFromBundle(args)
//                IsolateHolderService.setBackgroundFlutterView(backgroundFlutterView)
            }
        }
        backgroundChannel = MethodChannel(backgroundFlutterView, BACKGROUND_CHANNEL_ID)
        backgroundChannel.setMethodCallHandler(this)
        Log.e(LocationManagerPlugin.TAG, "startLocatorService Executed")

    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {

        Log.e(LocationManagerPlugin.TAG, "Service onMethodCall ${call.method}")
        when (call.method) {
            METHOD_SERVICE_INITIALIZED -> {
                synchronized(serviceStarted) {
                    while (locationUpdateQueue.isNotEmpty()) {
                        sendLocationEvent(locationUpdateQueue.remove())
                    }
                    while (geofenceEventQueue.isNotEmpty()) {
                        sendGeoFenceEvent(geofenceEventQueue.remove())
                    }
                    serviceStarted.set(true)
                }
            }
            else -> result.notImplemented()
        }
        result.success(null)
    }

    override fun onHandleWork(intent: Intent) {
        if (ACTION_LOCATION_UPDATE == intent.action) {
            handleLocationUpdate(intent)
        }
        if (ACTION_GEOFENCE_EVENT == intent.action) {
            handleGeoFenceEvent(intent)
        }
    }

    private fun handleGeoFenceEvent(intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        Log.e(LocationManagerPlugin.TAG, " GeoFencing Event Received")
        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceErrorMessages.getErrorString(this,
                    geofencingEvent.errorCode)
            Log.e(LocationManagerPlugin.TAG, " GeoFencing Event Error: $errorMessage")

            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER || geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.e(LocationManagerPlugin.TAG, " GeoFencing Event: $geofenceTransition")
            val event = getTransitionString(geofenceTransition)
            val location = geofencingEvent.triggeringLocation
            var speedAccuracy = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                speedAccuracy = location.speedAccuracyMetersPerSecond
            }
            val locationMap: HashMap<Any, Any> =
                    hashMapOf(ARG_LATITUDE to location.latitude,
                            ARG_LONGITUDE to location.longitude,
                            ARG_ACCURACY to location.accuracy,
                            ARG_ALTITUDE to location.altitude,
                            ARG_SPEED to location.speed,
                            ARG_SPEED_ACCURACY to speedAccuracy,
                            ARG_HEADING to location.bearing)
            val callback = getGeoFenceEventHandle(context)
            val result: HashMap<Any, Any> = hashMapOf(ARG_GEOFENCE_EVENT to callback, "geofenceIds" to (geofencingEvent.triggeringGeofences?.map { it.requestId }
                    ?: mutableListOf<String>()), "geoFenceEvent" to event, ARG_LOCATION to locationMap)
            synchronized(serviceStarted) {
                if (!serviceStarted.get()) {
                    geofenceEventQueue.add(result)
                } else {
                    sendGeoFenceEvent(result)
                }
            }
        }
    }

    private fun sendGeoFenceEvent(result: HashMap<Any, Any>) {
        Log.e(LocationManagerPlugin.TAG, "Sending GeoFence Event")
        Handler(mainLooper)
                .post {
                    backgroundChannel.invokeMethod(ARG_GEOFENCE_EVENT, result)
                }
    }

    private fun getTransitionString(transitionType: Int): String {
        return when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTERED"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXITED"
            else -> "UNKNOWN"
        }
    }

    private fun handleLocationUpdate(intent: Intent) {
        Log.e(LocationManagerPlugin.TAG, "Service Location Result: ${LocationResult.hasResult(intent)}")
        if (LocationResult.hasResult(intent)) {
            val location = LocationResult.extractResult(intent).lastLocation
            var speedAccuracy = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                speedAccuracy = location.speedAccuracyMetersPerSecond
            }
            val locationMap: HashMap<Any, Any> =
                    hashMapOf(ARG_LATITUDE to location.latitude,
                            ARG_LONGITUDE to location.longitude,
                            ARG_ACCURACY to location.accuracy,
                            ARG_ALTITUDE to location.altitude,
                            ARG_SPEED to location.speed,
                            ARG_SPEED_ACCURACY to speedAccuracy,
                            ARG_HEADING to location.bearing)
            Log.e(LocationManagerPlugin.TAG, "Service Location Map: ${locationMap.toString()}")
            val callback = getLocationUpdateHandle(context)
            val result: HashMap<Any, Any> = hashMapOf(ARG_LOCATION_UPDATE to callback, ARG_LOCATION to locationMap)
            synchronized(serviceStarted) {
                if (!serviceStarted.get()) {
                    locationUpdateQueue.add(result)
                } else {
                    sendLocationEvent(result)
                }
            }
        }
    }

    private fun sendLocationEvent(result: HashMap<Any, Any>) {
        Log.e(LocationManagerPlugin.TAG, "Sending Location Event")
        Handler(mainLooper)
                .post {
                    backgroundChannel.invokeMethod(ARG_LOCATION_UPDATE, result)
                }
    }
}

