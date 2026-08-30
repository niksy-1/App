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
        val workStart = System.currentTimeMillis()
        fun elapsed() = "${System.currentTimeMillis() - workStart}ms"

        try {
            // 1. Immediate Ping — record the moment we asked, so we can tell a fresh
            // Firestore write apart from whatever the target's doc already contained.
            val pingStartTime = System.currentTimeMillis()
            Log.d("RefreshWorker", "[${elapsed()}] Sending ping to target...")
            val pingSuccess = controller.requestTargetLocation(targetToken)
            Log.d("RefreshWorker", "[${elapsed()}] Ping call returned: success=$pingSuccess")
            if (!pingSuccess) {
                updateWidgetStatus("Ping failed")
                return Result.failure()
            }

            // 2. Wait for target coordinates. minTimestamp guarantees we don't resolve
            // on a stale cached snapshot that predates this ping. 20s gives headroom
            // for the target device's fast lastLocation write plus network variance —
            // see RadarMessagingService.fetchAndUploadLocation() for the matching
            // fast-path optimization on the sending side.
            Log.d("RefreshWorker", "[${elapsed()}] Waiting for target's Firestore snapshot (timeout=20s)...")
            val targetLoc = withTimeoutOrNull(20.seconds) {
                controller.observeTargetLocation(targetToken, minTimestamp = pingStartTime).first()
            }

            if (targetLoc == null) {
                Log.w("RefreshWorker", "[${elapsed()}] Timed out waiting for target's location update.")
                updateWidgetStatus("Target timed out")
                return Result.failure()
            }
            Log.d("RefreshWorker", "[${elapsed()}] Target location received: lat=${targetLoc.latitude}, lng=${targetLoc.longitude}")

            // 3. Location Optimization: Grab lastLocation first for instant response
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasLocationPermission) {
                Log.e("RefreshWorker", "[${elapsed()}] ACCESS_FINE_LOCATION not granted.")
                updateWidgetStatus("Loc Permission Denied")
                return Result.failure()
            }

            // Grab lastLocation cheaply first and push it right away so the widget
            // moves off "Pinging target..." promptly — a fresh high-accuracy fix can
            // take 10s+ (see the target-side note in RadarMessagingService), and
            // leaving the widget on stale status text for that whole stretch reads as
            // stuck/broken even though the ping itself already succeeded.
            Log.d("RefreshWorker", "[${elapsed()}] Requesting own lastLocation...")
            val lastLoc = fusedClient.lastLocation.await()
            if (lastLoc != null) {
                Log.d("RefreshWorker", "[${elapsed()}] Using lastLocation as an immediate interim update.")
                writeLocationPrefs(lastLoc, targetLoc)
                RadarWidget().updateAll(context)
            } else {
                Log.w("RefreshWorker", "[${elapsed()}] Own lastLocation was null.")
            }

            // 4. Get fresh location for high accuracy — this refines the interim
            // lastLocation-based update above with a more precise fix, once it's
            // ready. The widget already shows something useful by this point.
            Log.d("RefreshWorker", "[${elapsed()}] Requesting own fresh high-accuracy location...")
            val cts = CancellationTokenSource()
            val myLoc = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()

            var pushed = false
            if (myLoc != null) {
                Log.d("RefreshWorker", "[${elapsed()}] Fresh own location received.")
                writeLocationPrefs(myLoc, targetLoc)
                pushed = true
            } else if (lastLoc == null) {
                Log.e("RefreshWorker", "[${elapsed()}] Both lastLocation and fresh GPS fix failed.")
                writeStatusPrefs("GPS fix failed")
            } else {
                Log.d("RefreshWorker", "[${elapsed()}] Fresh fix was null; keeping the lastLocation fallback already written.")
                // We already have lastLoc's data written to prefs; push it now since
                // no fresher fix arrived.
                pushed = true
            }
            if (pushed) RadarWidget().updateAll(context)
            Log.d("RefreshWorker", "[${elapsed()}] doWork() completed. pushed=$pushed")

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation (e.g. this run was superseded) is not a
            // real failure — rethrow rather than reporting a misleading status.
            Log.w("RefreshWorker", "[${elapsed()}] Work was cancelled — not reporting as a failure.")
            throw e
        } catch (e: Exception) {
            Log.e("RefreshWorker", "[${elapsed()}] Error during widget refresh", e)
            writeStatusPrefs("Refresh error")
            RadarWidget().updateAll(context)
            return Result.failure()
        }

        return Result.success()
    }

    // Writes distance/status/target fields to prefs WITHOUT touching the widget UI.
    // Call RadarWidget().updateAll(context) once, separately, when you actually want
    // the host to redraw — see doWork() above.
    private fun writeLocationPrefs(myLoc: android.location.Location, targetLoc: RadarController.TargetLocation) {
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
    }

    private fun writeStatusPrefs(status: String) {
        context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_widget_status", status)
            .apply()
    }

    private suspend fun updateWidgetStatus(status: String) {
        writeStatusPrefs(status)
        RadarWidget().updateAll(context)
    }

    companion object {
        // Unique work: KEEP (not REPLACE) — if the button is tapped again while a
        // ping is already in flight, the new request is simply dropped rather than
        // cancelling the run that's already partway through a network round trip.
        // REPLACE was cancelling in-flight Cloud Function calls and widget renders
        // mid-flight (surfacing as JobCancellationException), which is what made
        // both the ping and the widget's background render feel flaky — a stray
        // repeat tap could tear down a run that would otherwise have succeeded.
        fun enqueue(context: Context) {
            Log.d("RefreshWorker", "Enqueuing Expedited RefreshWorker (unique, KEEP).")
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(androidx.work.workDataOf("is_periodic" to false))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "RadarOneTimeRefresh",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
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