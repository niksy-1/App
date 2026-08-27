package com.nikhil.app
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import com.nikhil.app.ui.theme.MyApplicationTheme
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PairingScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
@Composable
fun PairingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
    var myToken by remember { mutableStateOf("Fetching from Firebase...") }
    var partnerTokenInput by remember { mutableStateOf(sharedPrefs.getString("PARTNER_FCM_TOKEN", "") ?: "") }
    var saveStatus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                myToken = "Failed to fetch token. Check your google-services.json."
                return@addOnCompleteListener
            }
            myToken = task.result
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Your Radar Beacon (FCM Token):",
            style = MaterialTheme.typography.titleMedium
        )
        SelectionContainer {
            Text(
                text = myToken,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        HorizontalDivider()

        Text(
            text = "Target's Beacon ID:",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = partnerTokenInput,
            onValueChange = {
                partnerTokenInput = it
                saveStatus = ""
            },
            label = { Text("Paste Target Token Here") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = {
                sharedPrefs.edit().putString("PARTNER_FCM_TOKEN", partnerTokenInput).apply()
                saveStatus = "Target locked and saved."
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Target")
        }

        if (saveStatus.isNotEmpty()) {
            Text(
                text = saveStatus,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}