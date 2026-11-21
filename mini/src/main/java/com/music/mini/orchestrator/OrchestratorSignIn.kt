package com.music.mini.orchestrator

import android.content.Context
import android.content.Intent
import com.music.mini.Mini
import com.music.mini.Prefs
import com.music.mini.net.Api
import com.music.mini.net.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How SuperAwesomeMagicalOrchestrator's agent is reached (the phone's, or a stand-in in tests). */
interface OrchestratorAgent {
    fun status(): OrchestratorLink.Status?

    fun signIn(): OrchestratorLink.SignIn

    /** The person agreed to sign in to [ticket]'s server through the orchestrator app as it is now. */
    fun confirmed(ticket: OrchestratorLink.SignIn.Ticket)
}

/**
 * Signing in through SuperAwesomeMagicalOrchestrator, as the Connect app does it
 * (connect/.../orchestrator/OrchestratorSignIn.kt), in the smallest form: where someone is signed
 * in to it on this phone, Mini asks its agent for a short-lived ticket, hands that to its own
 * server, and is signed in as the same person. The sign-in screen shows one row for it,
 * "Continue as Maddie". Any failure is the usual sign-in, with no error shown.
 */
class OrchestratorSignIn(
    private val agent: OrchestratorAgent,
    private val api: Api,
    private val prefs: Prefs,
    private val deviceName: () -> String = Mini::deviceName,
) {
    /** The row on the sign-in screen: its words, and under them the server a press would sign in to, when that's being asked. */
    data class Row(val label: String, val place: String? = null)

    /** What came of asking. */
    sealed interface Outcome {
        /** Signed in, as [name]. */
        data class SignedIn(val name: String) : Outcome

        /** Open the orchestrator's page asking whether Mini may sign in as them, for a result. */
        data class Approve(val page: Intent) : Outcome

        /** Nothing more to do now: show [row] as it is. */
        data object Shown : Outcome
    }

    /** Why the agent is being asked. */
    enum class Why { START, TAPPED, APPROVED }

    /** The row to show now (null: none). */
    @Volatile
    var row: Row? = null
        private set

    @Volatile private var triedAtStart = false
    @Volatile private var gaveUp = false
    @Volatile private var approvalWaiting = false
    @Volatile private var status: OrchestratorLink.Status? = null

    /** The server whose address the row shows, waiting for the person's press. */
    @Volatile private var shownUrl: String? = null

    /**
     * The sign-in screen came up: who is signed in to the orchestrator, and (once since the app
     * started, and never after signing out on purpose) a quiet try at signing in.
     */
    suspend fun opened(): Outcome {
        if (prefs.signedIn) return Outcome.Shown
        status = asked { agent.status() }
        show()
        val quietly = !prefs.signedOutOnPurpose && !triedAtStart
        triedAtStart = true
        return if (quietly) run(Why.START) else Outcome.Shown
    }

    /** The person chose the row. */
    suspend fun tapped(): Outcome = run(Why.TAPPED)

    /** The person said yes on the orchestrator's page. */
    suspend fun approved(): Outcome = run(Why.APPROVED)

    /** The orchestrator's page couldn't be opened: the usual sign-in. */
    fun unavailable() {
        fellBack()
        show()
    }

    private suspend fun run(why: Why): Outcome {
        if (why == Why.APPROVED) status = asked { agent.status() } ?: status
        val answer = asked { agent.signIn() } ?: OrchestratorLink.SignIn.Unavailable("No answer")
        val outcome = when (answer) {
            is OrchestratorLink.SignIn.Ticket -> {
                approvalWaiting = false
                // A ticket to confirm is only ever used on a press, and only for the very address
                // the row was showing: Mini plays from whatever server it signs in to.
                if (!uses(answer, why, shownUrl)) {
                    shownUrl = answer.url
                    Outcome.Shown
                } else {
                    if (answer.confirm) asked { agent.confirmed(answer) }
                    shownUrl = null
                    handIn(answer) ?: run {
                        // Didn't work here (no account, an older server, no network): no row to try in vain.
                        fellBack()
                        Outcome.Shown
                    }
                }
            }
            // Its page only opens when the person asked for it; never by itself, and never twice in a row.
            is OrchestratorLink.SignIn.NeedsApproval -> {
                approvalWaiting = true
                if (why == Why.TAPPED) Outcome.Approve(answer.intent) else Outcome.Shown
            }
            is OrchestratorLink.SignIn.Unavailable -> {
                if (why != Why.START) fellBack()
                Outcome.Shown
            }
        }
        show()
        return outcome
    }

    /** Hands the ticket to its server and keeps the sign-in as signing in with a code does; null if that didn't work. */
    private suspend fun handIn(ticket: OrchestratorLink.SignIn.Ticket): Outcome.SignedIn? = try {
        val server = Api.normalize(ticket.url) ?: throw ServerException(0, "The orchestrator gave no server address")
        val signedIn = api.orchestratorSignIn(server, ticket.ticket, deviceName())
        val token = signedIn.token ?: throw ServerException(0, "The server sent no sign-in")
        // Signed in already; the server's name is only for show.
        val name = try {
            api.authConfig(server).serverName
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "SuperAwesomeMagicalMusic"
        }
        prefs.signIn(server, token, signedIn.user, name)
        Outcome.SignedIn(signedIn.user.displayName.ifBlank { signedIn.user.username })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun fellBack() {
        gaveUp = true
        approvalWaiting = false
        shownUrl = null
    }

    private fun show() {
        val url = shownUrl
        val label = if (gaveUp) null else label(approvalWaiting, status, confirming = url != null)
        row = label?.let { Row(it, url?.let(::place)) }
    }

    /** The agent is another app, which may ask its server: never on the main thread. Null when it fails. */
    private suspend fun <T> asked(block: () -> T): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** This app's name in the orchestrator's list of the family's apps (Mini signs in to the Music server). */
        const val APP = "music"
        const val WITH_ORCHESTRATOR = "Continue with SuperAwesomeMagicalOrchestrator"

        /**
         * Whether [ticket] is handed in now. One that needs confirming: only on a press, and only
         * when it names the address the row was showing ([shownUrl]).
         */
        fun uses(ticket: OrchestratorLink.SignIn.Ticket, why: Why, shownUrl: String?): Boolean =
            !ticket.confirm || (why == Why.TAPPED && shownUrl == ticket.url)

        /** The row's words, or null for no row. (The orchestrator only says who is signed in once Mini has been approved there.) */
        fun label(approvalWaiting: Boolean, status: OrchestratorLink.Status?, confirming: Boolean = false): String? {
            val name = status?.takeIf { it.signedIn }?.let { it.displayName.ifBlank { it.username } }.orEmpty().trim()
            return when {
                approvalWaiting -> WITH_ORCHESTRATOR
                name.isNotEmpty() -> "Continue as $name"
                confirming || status?.signedIn == true -> WITH_ORCHESTRATOR
                else -> null
            }
        }

        /** "at music.example.com": the server a press would sign in to. */
        fun place(url: String): String {
            val address = url.toHttpUrlOrNull()
            val host = when {
                address == null -> url
                address.port == HttpUrl.defaultPort(address.scheme) -> address.host
                else -> "${address.host}:${address.port}"
            }
            return "at $host"
        }

        /** The orchestrator on this phone, through the family's kit ([OrchestratorLink]). */
        fun onThisPhone(context: Context): OrchestratorAgent = object : OrchestratorAgent {
            override fun status(): OrchestratorLink.Status? = OrchestratorLink.status(context)

            override fun signIn(): OrchestratorLink.SignIn = OrchestratorLink.signIn(context, APP)

            override fun confirmed(ticket: OrchestratorLink.SignIn.Ticket) = OrchestratorLink.confirmed(context, ticket)
        }
    }
}
