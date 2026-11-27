package com.music.mini

import android.app.Application
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.music.mini.net.Api
import com.music.mini.net.CodeSignIn
import com.music.mini.orchestrator.OrchestratorSignIn
import com.music.mini.playback.ControllerPlayer
import com.music.mini.playback.MiniPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MiniApp : Application() {
    val mini: Mini by lazy { Mini(this) }
}

val Context.mini: Mini get() = (applicationContext as MiniApp).mini

/** What the app is made of, built once. Tests swap [player] for their own. */
class Mini(private val context: Context) {
    val prefs = Prefs(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Api.authInterceptor({ prefs.server }, { prefs.token }))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    val api = Api(http, { prefs.server }, { prefs.token })

    fun codeSignIn() = CodeSignIn(api)

    /** Signing in as whoever is signed in to SuperAwesomeMagicalOrchestrator on this phone. Tests swap it for their own. */
    var orchestrator = OrchestratorSignIn(OrchestratorSignIn.onThisPhone(context), api, prefs)

    var player: MiniPlayer = ControllerPlayer(context) { prefs.server }

    /** When the sleep timer stops the music (0 when it's off). */
    val sleepAt = MutableStateFlow(0L)
    private var sleep: Job? = null

    fun sleepIn(minutes: Int) {
        sleep?.cancel()
        if (minutes <= 0) {
            sleepAt.value = 0
            return
        }
        sleepAt.value = System.currentTimeMillis() + minutes * 60_000L
        sleep = scope.launch {
            delay(minutes * 60_000L)
            player.pause()
            sleepAt.value = 0
        }
    }

    fun tell(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    companion object {
        /** How the server lists this phone: "Mini on TCL Flip Pro". */
        fun deviceName(): String {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            val phone = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
            return "Mini on ${phone.ifBlank { "a phone" }}".take(60)
        }
    }
}
