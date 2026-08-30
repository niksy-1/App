package com.nikhil.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.messaging.FirebaseMessaging
import com.nikhil.app.ui.theme.RadarTheme
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ensure periodic sync is scheduled
        RefreshWorker.schedulePeriodicSync(this)

        setContent {
            RadarTheme {
                LocationPermissionWrapper {
                    MainNavigationWrapper()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigationWrapper() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)

    var myToken by remember { mutableStateOf("Fetching...") }
    var partnerTokenInput by remember { mutableStateOf(sharedPrefs.getString("PARTNER_FCM_TOKEN", "") ?: "") }
    var saveStatus by remember { mutableStateOf("") }
    var useBgImage by remember { mutableStateOf(sharedPrefs.getBoolean("use_bg_image", false)) }

    val cropImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val resultUri = result.data?.let { UCrop.getOutput(it) }
            resultUri?.let { uri ->
                scope.launch {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (bitmap != null) {
                            saveWidgetBackground(bitmap, context, sharedPrefs)
                            useBgImage = true
                            RadarWidget().updateAll(context)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to process cropped image", e)
                    }
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            val destinationUri = Uri.fromFile(File(context.cacheDir, "temp_crop.jpg"))
            val options = UCrop.Options().apply {
                setCompressionFormat(Bitmap.CompressFormat.JPEG)
                setCompressionQuality(85)
                setHideBottomControls(false)
                setFreeStyleCropEnabled(false)
                setStatusBarColor(android.graphics.Color.BLACK)
                setToolbarColor(android.graphics.Color.BLACK)
                setToolbarWidgetColor(android.graphics.Color.WHITE)
                setToolbarTitle("Crop for Widget")
                setActiveControlsWidgetColor(0xFFC97B8C.toInt())
                setRootViewBackgroundColor(android.graphics.Color.BLACK)
            }

            cropImageLauncher.launch(
                UCrop.of(sourceUri, destinationUri)
                    .withAspectRatio(2f, 1f)
                    .withMaxResultSize(1000, 500)
                    .withOptions(options)
                    .getIntent(context)
            )
        }
    }

    LaunchedEffect(Unit) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            myToken = if (task.isSuccessful) task.result else "Failed to fetch token."
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Radar Settings", style = MaterialTheme.typography.headlineSmall)
                    HorizontalDivider()
                    Text("Target Configuration", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = partnerTokenInput,
                        onValueChange = { partnerTokenInput = it; saveStatus = "" },
                        label = { Text("Target Beacon ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            sharedPrefs.edit().putString("PARTNER_FCM_TOKEN", partnerTokenInput).apply()
                            saveStatus = "Target saved."
                            RefreshWorker.schedulePeriodicSync(context)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Target")
                    }
                    if (saveStatus.isNotEmpty()) {
                        Text(saveStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Text("Your Beacon ID", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Text(text = myToken, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()

                    Text("Widget Background", style = MaterialTheme.typography.titleMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Use Image Background", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = useBgImage,
                            onCheckedChange = {
                                useBgImage = it
                                sharedPrefs.edit().putBoolean("use_bg_image", it).apply()
                                scope.launch { RadarWidget().updateAll(context) }
                            }
                        )
                    }

                    if (useBgImage) {
                        Button(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Text("Change Background Image")
                        }
                    } else {
                        var hexInput by remember {
                            val currentBg = sharedPrefs.getInt("widget_bg_color", 0xFF180F16.toInt())
                            mutableStateOf("%06X".format(0xFFFFFF and currentBg))
                        }

                        OutlinedTextField(
                            value = hexInput,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6)
                                hexInput = filtered
                                if (filtered.length == 6) {
                                    try {
                                        val colorInt = android.graphics.Color.parseColor("#$filtered")
                                        sharedPrefs.edit()
                                            .putInt("widget_bg_color", colorInt)
                                            .putBoolean("use_bg_image", false)
                                            .apply()
                                        useBgImage = false
                                        scope.launch { RadarWidget().updateAll(context) }
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Invalid hex color", e)
                                    }
                                }
                            },
                            label = { Text("Background Hex (#)") },
                            placeholder = { Text("180F16") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            prefix = { Text("#") }
                        )

                        val colors = listOf(
                            Color(0xFF180F16) to "Plum Black",
                            Color(0xFF3D1F2B) to "Wine",
                            Color(0xFF362A42) to "Lavender Dusk",
                            Color(0xFFF5EDE6) to "Parchment"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            colors.forEach { (color, _) ->
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable {
                                            val colorInt = color.toArgb()
                                            hexInput = "%06X".format(0xFFFFFF and colorInt)
                                            sharedPrefs.edit()
                                                .putInt("widget_bg_color", colorInt)
                                                .putBoolean("use_bg_image", false)
                                                .apply()
                                            useBgImage = false
                                            scope.launch { RadarWidget().updateAll(context) }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Radar") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { innerPadding ->
            PairingScreen(
                modifier = Modifier.padding(innerPadding),
                partnerToken = partnerTokenInput
            )
        }
    }
}

private fun saveWidgetBackground(bitmap: Bitmap, context: Context, prefs: SharedPreferences) {
    val maxDimension = 1000
    val ratio = maxDimension.toFloat() / Math.max(bitmap.width, bitmap.height)
    val scaledBitmap = if (ratio < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    } else {
        bitmap
    }
    val file = File(context.filesDir, "widget_bg.jpg")
    val outputStream = FileOutputStream(file)
    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
    outputStream.close()
    prefs.edit()
        .putString("widget_bg_image_path", file.absolutePath)
        .putBoolean("use_bg_image", true)
        .apply()
}

@Composable
fun LocationPermissionWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    // POST_NOTIFICATIONS is a separate runtime permission on API 33+, needed for the
    // "Remind to Charge" heads-up alert to actually display on the receiving device.
    // It's independent of location, so request it opportunistically without gating
    // the rest of the UI on it — a denial here shouldn't block using the radar.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* no-op: RadarMessagingService checks the permission again before posting */ }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (hasPermission) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }) {
                Text("Enable Location for Radar")
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun PairingScreen(modifier: Modifier = Modifier, partnerToken: String) {
    val context = LocalContext.current
    val radarController = remember { RadarController() }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val scope = rememberCoroutineScope()
    val deviceAzimuth by rememberDeviceAzimuth()
    var pingStatus by remember { mutableStateOf("Standby.") }
    var distanceStatus by remember { mutableStateOf("") }
    var batteryStatus by remember { mutableStateOf("") }
    var lastPingTime by remember { mutableStateOf("") }
    var targetLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var relativeAngle by remember { mutableFloatStateOf(0f) }
    var reminderStatus by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { radarController.stopListening() } }

    LaunchedEffect(partnerToken, targetLocation) {
        if (partnerToken.isNotEmpty() && targetLocation != null) {
            radarController.observeTargetLocation(partnerToken).collect { targetLoc ->
                targetLoc?.let {
                    if (it.batteryPercent != -1) {
                        val icon = if (it.isCharging) "⚡" else "🔋"
                        batteryStatus = "Target Battery: $icon ${it.batteryPercent}%"
                    }
                    if (it.timestamp > 0) {
                        lastPingTime = "Last ping: ${formatRelativeTime(it.timestamp)}"
                    }
                }
            }
        }
    }

    LaunchedEffect(deviceAzimuth, targetLocation) {
        targetLocation?.let { (tLat, tLng) ->
            val cts = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { myLoc ->
                    if (myLoc != null) {
                        val (dist, relBearing) = RadarController.computeRelativeBearingAndDistance(myLoc, tLat, tLng, deviceAzimuth)
                        relativeAngle = relBearing
                        distanceStatus = if (dist < 1000) "Distance: %.1f m".format(dist) else "Distance: %.2f km".format(dist / 1000)
                    }
                }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        RadarDisplay(relativeAngle = relativeAngle, isTargetAcquired = targetLocation != null)
        Spacer(modifier = Modifier.height(24.dp))
        if (distanceStatus.isNotEmpty()) {
            Text(text = distanceStatus, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (batteryStatus.isNotEmpty()) {
            Text(text = batteryStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    if (partnerToken.isEmpty()) {
                        reminderStatus = "Error: Set a Target ID first."
                        return@TextButton
                    }
                    reminderStatus = "Sending..."
                    scope.launch {
                        val sent = radarController.sendBatteryReminder(
                            partnerToken,
                            "Your partner asked you to plug in your phone."
                        )
                        reminderStatus = if (sent) "Reminder sent." else "Failed to send reminder."
                    }
                }
            ) {
                Text("Remind ⚡")
            }
            if (reminderStatus.isNotEmpty()) {
                Text(text = reminderStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        if (lastPingTime.isNotEmpty()) {
            Text(text = lastPingTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = pingStatus, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (partnerToken.isEmpty()) { pingStatus = "Error: Open Menu to set Target ID."; return@Button }
                    pingStatus = "Pinging target phone..."
                    radarController.requestTargetLocation(partnerToken) { success ->
                        if (success) {
                            pingStatus = "Ping delivered. Awaiting target..."
                            radarController.listenToTargetLocation(partnerToken) { targetLat, targetLng, _, _ ->
                                pingStatus = "Target locked!"
                                targetLocation = Pair(targetLat, targetLng)
                            }
                        } else { pingStatus = "Failed to send ping." }
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Text("Ping / Locate", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    if (partnerToken.isEmpty()) {
                        pingStatus = "Error: Open Menu to set Target ID."
                    } else {
                        RefreshWorker.enqueue(context)
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
            ) {
                Text("Sync Widget", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }

            if (targetLocation != null) {
                Button(
                    onClick = {
                        val (lat, lng) = targetLocation!!
                        val gmmIntentUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Target)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        context.startActivity(mapIntent)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                ) {
                    Text("Maps", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
        else -> {
            val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}