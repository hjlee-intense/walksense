package kr.co.intense.walksense

import io.flutter.embedding.android.FlutterActivity
import android.content.Intent
import android.os.Build
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        MethodChannel(messenger, "walksense/walking_service").setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "start" -> {
                        val intent = Intent(this, WalkingDetectionService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(null)
                    }
                    "stop" -> {
                        stopService(Intent(this, WalkingDetectionService::class.java))
                        result.success(null)
                    }
                    "isRunning" -> result.success(WalkingDetectionService.isRunning)
                    else -> result.notImplemented()
                }
            } catch (error: Exception) {
                result.error("SERVICE_ERROR", error.message, null)
            }
        }
        EventChannel(messenger, "walksense/walking_service_state")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    WalkingDetectionService.stateListener = { running, error ->
                        if (error != null) events.error("SERVICE_ERROR", error, null)
                        events.success(running)
                    }
                    events.success(WalkingDetectionService.isRunning)
                }

                override fun onCancel(arguments: Any?) {
                    WalkingDetectionService.stateListener = null
                }
            })
        EventChannel(messenger, "walksense/walking_status")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    WalkingDetectionService.walkingStatusListener = { status ->
                        events.success(status.name.lowercase())
                    }
                    events.success(WalkingDetectionService.walkingStatus.name.lowercase())
                }

                override fun onCancel(arguments: Any?) {
                    WalkingDetectionService.walkingStatusListener = null
                }
            })
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        WalkingDetectionService.stateListener = null
        WalkingDetectionService.walkingStatusListener = null
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "walksense/walking_service")
            .setMethodCallHandler(null)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "walksense/walking_service_state")
            .setStreamHandler(null)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "walksense/walking_status")
            .setStreamHandler(null)
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
