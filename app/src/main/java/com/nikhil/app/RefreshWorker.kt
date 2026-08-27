package com.nikhil.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class RefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "radar_refresh"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Radar Refresh"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Updating Radar")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        return ForegroundInfo(101, notification)
    }

    override suspend fun doWork(): Result {
        val isPeriodic = inputData.getBoolean("is_periodic", false)
        Log.d("RefreshWorker", "Worker started execution. Periodic: $isPeriodic")
        
        val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        val targetToken = prefs.getString("PARTNER_FCM_TOKEN", "") ?: ""

        if (targetToken.isEmpty()) {
            if (!isPeriodic) updateWidgetStatus("No target saved")
            return Result.success() // Success to avoid retries if no target is configured
        }

        val controller = RadarController()
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        try {
            // 1. Immediate Ping
            val pingSuccess = controller.requestTargetLocation(targetToken)
            if (!pingSuccess) {
                updateWidgetStatus("Ping failed")
                return Result.failure()
            }

            // 2. Wait for target coordinates
            val targetLoc = withTimeoutOrNull(15.seconds) {
                controller.observeTargetLocation(targetToken).first()
            }

            if (targetLoc == null) {
                updateWidgetStatus("Target timed out")
                return Result.failure()
            }

            // 3. Location Optimization: Grab lastLocation first for instant response
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasLocationPermission) {
                updateWidgetStatus("Loc Permission Denied")
                return Result.failure()
            }

            val lastLoc = fusedClient.lastLocation.await()
            if (lastLoc != null) {
                Log.d("RefreshWorker", "Using lastLocation for instant update.")
                processLocationUpdate(lastLoc, targetLoc)
            }

            // 4. Get fresh location for high accuracy
            val cts = CancellationTokenSource()
            val myLoc = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()

            if (myLoc != null) {
                processLocationUpdate(myLoc, targetLoc)
            } else if (lastLoc == null) {
                updateWidgetStatus("GPS fix failed")
            }

        } catch (e: Exception) {
            Log.e("RefreshWorker", "Error during widget refresh", e)
            updateWidgetStatus("Refresh error")
            return Result.failure()
        } finally {
            RadarWidget().updateAll(context)
        }

        return Result.success()
    }

    private suspend fun processLocationUpdate(myLoc: android.location.Location, targetLoc: RadarController.TargetLocation) {
        val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        val (dist, _) = RadarController.computeRelativeBearingAndDistance(
            currentLocation = myLoc,
            targetLat = targetLoc.latitude,
            targetLng = targetLoc.longitude,
            currentDeviceAzimuth = 0f
        )

        val distString = if (dist < 1000) "%.1f m".format(dist) else "%.2f km".format(dist / 1000)
        
        val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val statusString = "Updated at ${timeFormat.format(java.util.Date())}"

        prefs.edit()
            .putString("last_widget_distance", distString)
            .putString("last_widget_status", statusString)
            .putString("last_widget_lat", targetLoc.latitude.toString())
            .putString("last_widget_lng", targetLoc.longitude.toString())
            .putInt("last_widget_battery", targetLoc.batteryPercent)
            .putBoolean("last_widget_is_charging", targetLoc.isCharging)
            .putLong("last_success_timestamp", System.currentTimeMillis())
            .apply()
        
        // Immediate UI push
        RadarWidget().updateAll(context)
    }

    private suspend fun updateWidgetStatus(status: String) {
        context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_widget_status", status)
            .apply()
        RadarWidget().updateAll(context)
    }

    companion object {
        fun enqueue(context: Context) {
            Log.d("RefreshWorker", "Enqueuing Expedited RefreshWorker.")
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun schedulePeriodicSync(context: Context) {
            Log.d("RefreshWorker", "Scheduling Periodic RefreshWorker.")
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("RadarPeriodicSync")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "RadarPeriodicSync",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
