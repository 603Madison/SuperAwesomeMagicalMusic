package com.music.connect.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.connect.R
import com.music.connect.net.AuthConfig
import com.music.connect.net.DeviceStart
import com.music.connect.net.ServerApi
import com.music.connect.orchestrator.OrchestratorSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sign-in: the server's address, then single sign-on in a browser tab, or a password.
 * [orchestratorOffer]: what the orchestrator's button says, for drawing the screen in tests
 * (otherwise as [OrchestratorSignIn] has it).
 */
@Composable
fun SignInScreen(finishing: Boolean, error: String?, orchestratorOffer: OrchestratorSignIn.Offer? = null) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(c.settings.value.server.removePrefix("https://")) }
    var config by remember { mutableStateOf<Pair<String, AuthConfig>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(error) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    // Signing in with a code typed on another device: the code, once the server has given one.
    var code by remember { mutableStateOf<DeviceStart?>(null) }
    var codeSignIn by remember { mutableStateOf<Job?>(null) }
    // "Continue as Maddie", when someone is signed in to SuperAwesomeMagicalOrchestrator on this phone.
    val orchestrator = orchestratorOffer ?: c.orchestratorSignIn.offer.collectAsState().value
    // The orchestrator's page asking whether this app may sign in as them: yes, and the app asks again.
    val approval = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) c.orchestratorSignIn.approved(c.scope)
    }
    LaunchedEffect(Unit) {
        c.orchestratorSignIn.approvals.collect { page ->
            try {
                approval.launch(page)
            } catch (e: android.content.ActivityNotFoundException) {
                c.orchestratorSignIn.unavailable()
            }
        }
    }

    Backdrop {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        // The launcher icon is adaptive (painterResource can't draw those): its parts, drawn here.
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(colorResource(R.color.launcher_background))) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Text(config?.second?.serverName ?: "SuperAwesomeMagicalMusic", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            if (config == null) "Sign in to your server to bring in your music, radio and playlists." else "Sign in to listen",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        if (finishing) {
            CircularProgressIndicator()
            Text("Finishing signing in…")
            return@Column
        }
        // Above the usual ways: one tap, as whoever is signed in to the orchestrator here.
        if (codeSignIn == null) OrchestratorButton(orchestrator.label, orchestrator.place) { c.orchestratorSignIn.tapped(c.scope) }
        val current = config
        if (current == null) {
            OutlinedTextField(
                address, { address = it },
                label = { Text("Server address") },
                placeholder = { Text("music.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && address.isNotBlank(),
                onClick = {
                    val server = ServerApi.normalize(address)
                    if (server == null) {
                        message = "That isn't a web address"
                        return@Button
                    }
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            config = server to c.api.authConfig(server)
                        } catch (e: Exception) {
                            message = "Couldn't reach a SuperAwesomeMagicalMusic server there (${e.message ?: e.javaClass.simpleName})"
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Checking…" else "Continue") }
        } else {
            val (server, auth) = current
            if (codeSignIn != null) {
                CodeSignInPanel(code) {
                    codeSignIn?.cancel()
                    codeSignIn = null
                    code = null
                }
                return@Column
            }
            auth.oidc?.let { oidc ->
                Button(onClick = { c.signIn.startBrowserSignIn(server) }, modifier = Modifier.fillMaxWidth()) { Text(oidc.label) }
            }
            if (auth.localLogin || showPassword || auth.oidc == null) {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                c.signIn.signInWithPassword(server, username.trim(), password)
                            } catch (e: Exception) {
                                message = e.message ?: "Couldn't sign in"
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Signing in…" else "Sign in with password") }
            } else {
                TextButton(onClick = { showPassword = true }) { Text("Sign in with a password (admins)") }
            }
            // No password to type: a code, allowed on a phone or computer that's signed in.
            TextButton(onClick = {
                message = null
                codeSignIn = scope.launch {
                    try {
                        c.signIn.signInWithCode(server) { code = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.message ?: "Couldn't sign in"
                    }
                    codeSignIn = null
                    code = null
                }
            }) { Text("Sign in with a code from another device") }
            TextButton(onClick = { config = null }) { Text("Use another server") }
            Text("Server ${auth.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}

/**
 * "Continue as Maddie" ([label]; null: no button), with the server it signs in to under it
 * ([place], "at music.example.com") when the person is being asked to agree to it.
 */
@Composable
fun OrchestratorButton(label: String?, place: String?, onClick: () -> Unit) {
    if (label == null) return
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
        Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, textAlign = TextAlign.Center)
            place?.let { Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
        }
    }
    Text("or", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The code to type on another device, while the app waits for it to be allowed there. */
@Composable
private fun CodeSignInPanel(code: DeviceStart?, onCancel: () -> Unit) {
    if (code == null) {
        CircularProgressIndicator()
        Text("Getting a code…")
    } else {
        Text(code.userCode, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, textAlign = TextAlign.Center)
        Text(
            "On a phone or computer where you're signed in, go to ${code.verificationUri.substringAfter("://")} and type this code.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        CircularProgressIndicator(Modifier.size(28.dp))
        Text("Waiting for you to allow it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = onCancel) { Text("Cancel") }
}
