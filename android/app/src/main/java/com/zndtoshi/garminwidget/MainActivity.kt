package com.zndtoshi.garminwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import com.zndtoshi.garminwidget.data.SettingsStore
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.ui.refreshResultText
import com.zndtoshi.garminwidget.widget.GarminWidgetReceiver
import com.zndtoshi.garminwidget.work.RefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigurationScreen(
                        settings = SettingsStore(this),
                        onRequestWidget = ::requestWidgetPin,
                    )
                }
            }
        }
    }

    private fun requestWidgetPin() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, GarminWidgetReceiver::class.java), null, null)
        }
    }
}

@Composable
private fun ConfigurationScreen(
    settings: SettingsStore,
    onRequestWidget: () -> Unit,
) {
    var backendUrl by remember { mutableStateOf(settings.backendUrl()) }
    var token by remember { mutableStateOf("") }
    var message by remember {
        mutableStateOf(if (settings.isConfigured()) "A private token is already saved." else "Enter your private widget token.")
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Garmin Widget", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your token is encrypted with Android Keystore and is never displayed again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = backendUrl,
            onValueChange = { backendUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Backend URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (settings.isConfigured()) "Replace token (optional)" else "Widget bearer token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                runCatching {
                    settings.save(backendUrl, token.takeIf(String::isNotBlank))
                }.onSuccess {
                    token = ""
                    message = "Saved securely. Refreshing the widget…"
                    scope.launch {
                        val requestId = RefreshScheduler.enqueue(context)
                        val refreshCompleted: Boolean = withContext(Dispatchers.IO) {
                            val deadlineMs = System.currentTimeMillis() + 45_000L
                            while (System.currentTimeMillis() < deadlineMs) {
                                val workInfo = WorkManager.getInstance(context)
                                    .getWorkInfoById(requestId)
                                    .get()
                                if (workInfo?.state?.isFinished == true) {
                                    return@withContext true
                                }
                                delay(200)
                            }
                            false
                        }

                        if (!refreshCompleted) {
                            message = "Refresh is still running. Check the widget again shortly."
                            return@launch
                        }

                        message = refreshResultText(WidgetStore(context).read().status)
                    }
                }.onFailure {
                    message = it.message ?: "Could not save configuration."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save and refresh")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRequestWidget, modifier = Modifier.fillMaxWidth()) {
            Text("Add widget to home screen")
        }
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
