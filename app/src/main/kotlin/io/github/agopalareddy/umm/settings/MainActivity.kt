package io.github.agopalareddy.umm.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.auth.SignInLauncher
import io.github.agopalareddy.umm.graph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AccountScreen() } }
    }

    @Composable
    private fun AccountScreen() {
        val store = graph.apiKeyStore
        val key by store.key.collectAsStateWithLifecycle()
        var pasted by remember { mutableStateOf("") }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Umm", style = MaterialTheme.typography.headlineMedium)
            Text(if (key != null) "Connected" else "Not connected")
            Button(onClick = { SignInLauncher.start(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) {
                Text("Connect with OpenRouter")
            }
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it.trim() },
                label = { Text("Paste key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { store.set(pasted); pasted = "" }, enabled = pasted.isNotEmpty()) { Text("Save key") }
            if (key != null) OutlinedButton(onClick = { store.clear() }) { Text("Disconnect") }
        }
    }
}
