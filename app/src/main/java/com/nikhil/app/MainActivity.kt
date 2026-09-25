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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radar
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.compose.material.icons.filled.Palette
import com.nikhil.app.ui.theme.RadarTheme
import com.nikhil.app.ui.theme.RadarThemeVariant
import com.nikhil.app.ui.theme.ThemePickerScreen
import com.nikhil.app.ui.theme.rememberThemeVariantState
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun getDisplayName(beaconId: String): String = when (beaconId) {
    "VG2S5glfWVbaZd2TxflIMZjcgPz1" -> "Niksy"
    "6voEdctatob28aCJG2MQAz9SaL82" -> "Miru"
    else -> if (beaconId == FirebaseAuth.getInstance().currentUser?.uid) "Me" else "Partner"
}

@Composable
private fun AuthenticatedRadar(content: @Composable () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    LaunchedEffect(user?.uid, attempt) {
        ready = false
        error = ""
        if (user == null) return@LaunchedEffect
        try {
            RadarSession.registerDevice()
            ready = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Could not connect securely. Check your connection and retry." }
    }
    if (user == null) {
        AccountSignIn()
    } else if (ready) {
        content()
    } else Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (error.isEmpty()) "Connecting securely…" else error)
        if (error.isNotEmpty()) Button(onClick = { attempt++ }) { Text("Retry") }
    }
}

@Composable
private fun AccountSignIn() {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Sign in to Radar", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use your account to keep your notes and partner connection when you change phones or reinstall.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; message = "" },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; message = "" },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        RadarSession.signIn(email, password)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        message = "Sign-in failed. Check your email and password."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Signing in…" else "Sign in") }
        TextButton(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        RadarSession.sendPasswordReset(email)
                        message = "If this email has an account, a reset link is on its way."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        message = "Could not send a reset link. Try again later."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && email.isNotBlank()
        ) { Text("Forgot password?") }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ensure periodic sync is scheduled
        RefreshWorker.schedulePeriodicSync(this)

        setContent {
            val (variant, setVariant) = rememberThemeVariantState()
            RadarTheme(themeVariant = variant) {
                AuthenticatedRadar {
                  LocationPermissionWrapper {
                    MainNavigationWrapper(
                        themeVariant = variant,
                        setThemeVariant = setVariant
                    )
                  }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigationWrapper(
    themeVariant: RadarThemeVariant,
    setThemeVariant: (RadarThemeVariant) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)

    val myUid = requireNotNull(FirebaseAuth.getInstance().currentUser).uid
    var approvedPartnerUid by remember { mutableStateOf("") }
    var pairingStatus by remember { mutableStateOf("Checking partner approval…") }
    var pairingBusy by remember { mutableStateOf(false) }
    var partnerEmailInput by remember { mutableStateOf("") }
    var incomingRequests by remember { mutableStateOf<List<RadarSession.PairingRequest>>(emptyList()) }
    var myNote by remember { mutableStateOf(sharedPrefs.getString("MY_NOTE", "") ?: "") }
    var saveStatus by remember { mutableStateOf("") }
    var noteStatus by remember { mutableStateOf("") }
    var noteBusy by remember { mutableStateOf(false) }
    var useBgImage by remember { mutableStateOf(sharedPrefs.getBoolean("use_bg_image", false)) }
    var showDistance by remember { mutableStateOf(sharedPrefs.getBoolean("show_distance", true)) }
    val radarController = remember { RadarController() }

    var currentScreen by remember { mutableStateOf(Screen.RADAR) }

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
        RadarSession.clearPartnerCache(context)
        while (true) {
            val partner = try { RadarSession.approvedPartner().orEmpty() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    pairingStatus = "Could not check partner approval. Reconnecting…"
                    delay(10000)
                    continue
                }
            if (approvedPartnerUid != partner) {
                radarController.stopListening()
                RadarSession.clearPartnerCache(context)
            }
            approvedPartnerUid = partner
            sharedPrefs.edit().putString("PARTNER_UID", partner).apply()
            pairingStatus = if (partner.isNotEmpty()) "Both partners approved. Sharing is active."
                else "Sharing is off until a connection request is accepted."
            incomingRequests = try { RadarSession.incomingRequests() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { incomingRequests }
            delay(10000)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Radar", style = MaterialTheme.typography.headlineSmall)
                    HorizontalDivider()

                    NavigationDrawerItem(
                        label = { Text("Radar View") },
                        selected = currentScreen == Screen.RADAR,
                        onClick = {
                            currentScreen = Screen.RADAR
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Radar, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Note History") },
                        selected = currentScreen == Screen.HISTORY,
                        onClick = {
                            currentScreen = Screen.HISTORY
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Appearance") },
                        selected = currentScreen == Screen.THEME,
                        onClick = {
                            currentScreen = Screen.THEME
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Palette, contentDescription = null) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Text("Configuration", style = MaterialTheme.typography.titleMedium)
                    Text(pairingStatus, style = MaterialTheme.typography.bodySmall)
                    if (approvedPartnerUid.isEmpty()) {
                        OutlinedTextField(
                            value = partnerEmailInput,
                            onValueChange = { partnerEmailInput = it; saveStatus = "" },
                            label = { Text("Partner email") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("Send a private connection request to your partner's Radar account.")
                        Button(
                            enabled = !pairingBusy && partnerEmailInput.isNotBlank(),
                            onClick = {
                                val email = partnerEmailInput.trim()
                                pairingBusy = true
                                scope.launch {
                                    try {
                                        val name = RadarSession.requestPartner(email)
                                        partnerEmailInput = ""
                                        saveStatus = "Request sent to $name. Sharing starts when they accept."
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) {
                                        saveStatus = e.message ?: "Could not send request. Try again."
                                    } finally { pairingBusy = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Send Connection Request") }
                    }
                    incomingRequests.forEach { request ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Connection request", style = MaterialTheme.typography.titleSmall)
                                Text(if (request.email.isNotBlank())
                                    "${request.displayName} (${request.email})" else request.displayName)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !pairingBusy,
                                        onClick = {
                                            pairingBusy = true
                                            scope.launch {
                                                try {
                                                    RadarSession.respondToRequest(request.requesterUid, true)
                                                    incomingRequests = incomingRequests.filterNot {
                                                        it.requesterUid == request.requesterUid
                                                    }
                                                    saveStatus = "Connected to ${request.displayName}."
                                                } catch (e: CancellationException) { throw e }
                                                catch (e: Exception) {
                                                    saveStatus = e.message ?: "Could not accept request."
                                                } finally { pairingBusy = false }
                                            }
                                        }
                                    ) { Text("Accept") }
                                    OutlinedButton(
                                        enabled = !pairingBusy,
                                        onClick = {
                                            pairingBusy = true
                                            scope.launch {
                                                try {
                                                    RadarSession.respondToRequest(request.requesterUid, false)
                                                    incomingRequests = incomingRequests.filterNot {
                                                        it.requesterUid == request.requesterUid
                                                    }
                                                    saveStatus = "Request declined."
                                                } catch (e: CancellationException) { throw e }
                                                catch (e: Exception) {
                                                    saveStatus = e.message ?: "Could not decline request."
                                                } finally { pairingBusy = false }
                                            }
                                        }
                                    ) { Text("Decline") }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        enabled = !pairingBusy && approvedPartnerUid.isNotEmpty(),
                        onClick = {
                            pairingBusy = true
                            scope.launch {
                                try {
                                    RadarSession.revoke()
                                    approvedPartnerUid = ""
                                    sharedPrefs.edit().remove("PARTNER_UID").apply()
                                    radarController.stopListening()
                                    RadarSession.clearPartnerCache(context)
                                    saveStatus = "Sharing revoked."
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { saveStatus = "Could not revoke sharing. Reconnect and retry." }
                                finally { pairingBusy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text("Stop Sharing") }
                    if (saveStatus.isNotEmpty()) {
                        Text(saveStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()

                    Text("Your Note", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = myNote,
                        onValueChange = { myNote = it.take(500); noteStatus = "" },
                        label = { Text("Note for partner") },
                        supportingText = { Text("${myNote.length}/500") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                    Button(
                        enabled = !noteBusy && myNote.isNotBlank() && approvedPartnerUid.isNotEmpty(),
                        onClick = {
                            if (myNote.isBlank()) return@Button
                            val trimmedNote = myNote.trim()
                            val trimmedPartner = approvedPartnerUid
                            sharedPrefs.edit().putString("MY_NOTE", trimmedNote).apply()
                            myNote = trimmedNote
                            noteStatus = "Posting..."
                            noteBusy = true
                            scope.launch {
                                try {
                                    radarController.updateMyNote(myUid, trimmedPartner, trimmedNote)
                                    noteStatus = "Note posted!"
                                } catch (e: CancellationException) { throw e }
                                catch (e: FirebaseFunctionsException) {
                                    noteStatus = if (e.code == FirebaseFunctionsException.Code.ALREADY_EXISTS)
                                        "You already sent that note in the last 10 minutes."
                                    else e.message ?: "Note not posted. Check your connection."
                                } catch (e: Exception) {
                                    noteStatus = "Note not posted. Check partner approval and connection."
                                } finally { noteBusy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Post Note")
                    }
                    if (noteStatus.isNotEmpty()) {
                        Text(noteStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()

                    Text("Widget Background", style = MaterialTheme.typography.titleMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Show Distance", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = showDistance,
                            onCheckedChange = {
                                showDistance = it
                                sharedPrefs.edit().putBoolean("show_distance", it).apply()
                                // Update widget immediately to reflect visibility change
                                scope.launch { RadarWidget().updateAll(context) }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Button(
                        onClick = {
                            if (approvedPartnerUid.isEmpty()) {
                                saveStatus = "Connect with your partner first."
                            } else {
                                sharedPrefs.edit()
                                    .putString("last_widget_status", "Pinging target...")
                                    .apply()
                                RefreshWorker.enqueue(context)
                                scope.launch { RadarWidget().updateAll(context) }
                                scope.launch {
                                    drawerState.close()
                                    snackbarHostState.showSnackbar("Widget refresh started")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sync Widget Now")
                    }
                    if (FirebaseAuth.getInstance().currentUser?.isAnonymous == false) {
                        HorizontalDivider()
                        TextButton(onClick = {
                            scope.launch {
                                RadarSession.clearPartnerCache(context)
                                sharedPrefs.edit()
                                    .remove("PARTNER_UID")
                                    .remove("MY_NOTE")
                                    .apply()
                                FirebaseAuth.getInstance().signOut()
                            }
                        }) { Text("Switch account") }
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (currentScreen == Screen.RADAR) "Radar" else "History") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { innerPadding ->
            when (currentScreen) {
                Screen.RADAR -> {
                    PairingScreen(
                        modifier = Modifier.padding(innerPadding),
                        partnerUid = approvedPartnerUid,
                        showDistance = showDistance
                    )
                }
                Screen.HISTORY -> {
                    HistoryScreen(
                        modifier = Modifier.padding(innerPadding),
                        myUid = myUid,
                        partnerUid = approvedPartnerUid,
                        radarController = radarController
                    )
                }
                Screen.THEME -> {
                    ThemePickerScreen(
                        modifier = Modifier.padding(innerPadding),
                        selected = themeVariant,
                        onVariantSelected = setThemeVariant
                    )
                }
            }
        }
    }
}

enum class Screen { RADAR, HISTORY, THEME }

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    myUid: String,
    partnerUid: String,
    radarController: RadarController
) {
    val historyFlow = remember(myUid, partnerUid) {
        radarController.observeNoteHistory(myUid, partnerUid).catch { emit(emptyList()) }
    }
    val history by historyFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    if (history.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No notes yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(history, key = { it.id }) { record ->
                val isMe = record.senderId == myUid
                val canEdit = isMe && nowMillis >= record.timestamp &&
                    nowMillis - record.timestamp < TimeUnit.MINUTES.toMillis(3)
                var editing by remember(record.id) { mutableStateOf(false) }
                var draft by remember(record.id) { mutableStateOf(record.text) }
                var editBusy by remember(record.id) { mutableStateOf(false) }
                var editStatus by remember(record.id) { mutableStateOf("") }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isMe) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            MaterialTheme.shapes.medium
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = getDisplayName(record.senderId),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatRelativeTime(record.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = record.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (editing && canEdit) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(500); editStatus = "" },
                            label = { Text("Edit note") },
                            supportingText = { Text("${draft.length}/500") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                            enabled = !editBusy
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !editBusy && draft.isNotBlank(),
                                onClick = {
                                    editBusy = true
                                    scope.launch {
                                        try {
                                            radarController.editNote(partnerUid, record.id, draft)
                                            editing = false
                                            editStatus = ""
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) {
                                            editStatus = e.message ?: "Could not edit this note."
                                        } finally { editBusy = false }
                                    }
                                }
                            ) { Text("Save") }
                            OutlinedButton(
                                enabled = !editBusy,
                                onClick = { editing = false; editStatus = "" }
                            ) { Text("Cancel") }
                        }
                    } else if (canEdit) {
                        TextButton(onClick = { draft = record.text; editing = true }) {
                            Text("Edit (first 3 minutes)")
                        }
                    }
                    if (editStatus.isNotEmpty()) {
                        Text(editStatus, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
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
fun PairingScreen(
    modifier: Modifier = Modifier,
    partnerUid: String,
    showDistance: Boolean
) {
    val context = LocalContext.current
    val radarController = remember { RadarController() }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val scope = rememberCoroutineScope()
    val deviceAzimuth by rememberDeviceAzimuth()
    var pingStatus by remember { mutableStateOf("Standby.") }
    var distanceStatus by remember { mutableStateOf("") }
    var batteryStatus by remember { mutableStateOf("") }
    var lastPingTime by remember { mutableStateOf("") }
    var partnerNote by remember { mutableStateOf("") }
    var targetLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var relativeAngle by remember { mutableFloatStateOf(0f) }
    var reminderStatus by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { radarController.stopListening() } }

    LaunchedEffect(partnerUid) {
        radarController.stopListening()
        partnerNote = ""
        batteryStatus = ""
        lastPingTime = ""
        targetLocation = null
        distanceStatus = ""
        if (partnerUid.isNotEmpty()) {
            radarController.observeTargetLocation(partnerUid).catch {
                partnerNote = ""
                batteryStatus = ""
                lastPingTime = ""
                targetLocation = null
                distanceStatus = ""
                pingStatus = "Sharing unavailable. Check partner approval."
            }.collect { targetLoc ->
                targetLoc?.let {
                    partnerNote = it.note ?: ""
                    if (it.batteryPercent != -1) {
                        val icon = if (it.isCharging) "⚡" else "🔋"
                        val name = getDisplayName(partnerUid)
                        val label = if (name.isNotEmpty()) "${name}'s" else "Target"
                        batteryStatus = "$label Battery: $icon ${it.batteryPercent}%"
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
        if (partnerNote.isNotEmpty()) {
            Text(
                text = "\"$partnerNote\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (showDistance && distanceStatus.isNotEmpty()) {
            Text(text = distanceStatus, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (batteryStatus.isNotEmpty()) {
            Text(text = batteryStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    if (partnerUid.isEmpty()) {
                        reminderStatus = "Connect with your partner first."
                        return@TextButton
                    }
                    reminderStatus = "Sending..."
                    scope.launch {
                        val sent = radarController.sendBatteryReminder(
                            partnerUid,
                            "Please plug in your phone 🥺"
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
                    if (partnerUid.isEmpty()) {
                        pingStatus = "Open the menu and connect with your partner first."
                        return@Button
                    }
                    pingStatus = "Pinging target phone..."
                    radarController.requestTargetLocation(partnerUid) { success ->
                        if (success) {
                            pingStatus = "Ping delivered. Awaiting target..."
                            radarController.listenToTargetLocation(partnerUid) { targetLat, targetLng, _, _ ->
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
