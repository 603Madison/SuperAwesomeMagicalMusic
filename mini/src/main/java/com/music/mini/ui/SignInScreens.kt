package com.music.mini.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.music.mini.Mini
import com.music.mini.R
import com.music.mini.net.Api
import com.music.mini.net.CodeSignIn
import com.music.mini.net.DeviceStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Signing in, step one: the server's address (typed once; remembered for next time). */
class ServerScreen : Screen() {
    override val title = "Sign in"
    override val centreLabel = "Continue"
    private lateinit var field: EditText
    private lateinit var status: TextView
    private var checking: Job? = null

    override fun create(context: Context): View {
        val c = context
        field = EditText(c).apply {
            setText(mini.prefs.lastServer.removePrefix("https://"))
            hint = "music.example.com"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(c.color(R.color.text))
            setHintTextColor(c.color(R.color.muted))
            setBackgroundResource(R.drawable.field_background)
            setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
            setSelection(text.length)
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        status = c.label("", 15f)
        val column = c.column(
            c.label("Your server's address", 16f, bold = true),
            field,
            c.label("Like music.example.com. No need to type https://", 14f, muted = true),
            c.button("Continue") { go() },
            status,
        )
        column.setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
        (column.getChildAt(3).layoutParams as LinearLayout.LayoutParams).topMargin = c.dp(8)
        return ScrollView(c).apply { addView(column) }
    }

    override fun defaultFocus(): View = field

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (field.hasFocus() && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            go()
            return true
        }
        return false
    }

    fun go() {
        if (checking?.isActive == true) return
        val server = Api.normalize(field.text.toString())
        if (server == null) {
            status.text = "Type your server's address first"
            return
        }
        status.text = "Checking ${server.substringAfter("://")}…"
        checking = scope.launch {
            try {
                val config = mini.api.authConfig(server)
                mini.prefs.rememberServer(server)
                status.text = ""
                host.push(CodeScreen(server, config.serverName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Couldn't reach a SuperAwesomeMagicalMusic server there. ${problem(e)}"
            }
        }
    }
}

/**
 * Signing in, step two: a code to type on a phone or computer that's signed in (the server's
 * /link page), and a QR code of that page. Signed in here as soon as it's allowed there.
 */
class CodeScreen(private val server: String, private val serverName: String) : Screen() {
    override val title = "Sign in with a code"
    override val optionsLabel = "New code"
    override val centreLabel = ""
    private lateinit var scroll: ScrollView
    private lateinit var code: TextView
    private lateinit var where: TextView
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var again: TextView
    private var signingIn: Job? = null

    /** The sign-in as the server started it (for tests). */
    var started: DeviceStart? = null
        private set

    override fun create(context: Context): View {
        val c = context
        code = c.label("····-····", 34f, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(c.color(R.color.focus))
        }
        where = c.label("", 16f).apply { gravity = Gravity.CENTER }
        status = c.label("Getting a code…", 15f, muted = true).apply { gravity = Gravity.CENTER }
        again = c.button("Get a new code") { start() }.apply { visibility = View.GONE }
        qr = ImageView(c).apply {
            adjustViewBounds = true
            contentDescription = "QR code of the sign-in page"
            visibility = View.GONE
        }
        val column = c.column(code, where, again, status, qr)
        column.setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
        (qr.layoutParams as LinearLayout.LayoutParams).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            width = LinearLayout.LayoutParams.WRAP_CONTENT
            topMargin = c.dp(8)
        }
        scroll = ScrollView(c).apply {
            isFocusable = true
            addView(column)
        }
        return scroll
    }

    override fun defaultFocus(): View = if (again.visibility == View.VISIBLE) again else scroll

    override fun onShown(first: Boolean) {
        super.onShown(first)
        if (first) start()
    }

    override fun onOptions() = start()

    private fun start() {
        signingIn?.cancel()
        again.visibility = View.GONE
        code.text = "····-····"
        where.text = ""
        qr.visibility = View.GONE
        status.text = "Getting a code…"
        scroll.requestFocus()
        val signIn: CodeSignIn = mini.codeSignIn()
        signingIn = scope.launch {
            val started = try {
                signIn.start(server, Mini.deviceName())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("Couldn't get a code. ${problem(e)}")
                return@launch
            }
            this@CodeScreen.started = started
            code.text = started.userCode
            where.text = "On your phone or computer, go to\n${started.verificationUri.substringAfter("://")}\nand type this code."
            status.text = "Waiting for you to allow it…"
            showQr(started.verificationUriComplete)
            when (val outcome = signIn.await(server, started)) {
                is CodeSignIn.Outcome.SignedIn -> {
                    mini.prefs.signIn(server, outcome.token, outcome.user, serverName)
                    mini.tell("Signed in as ${outcome.user.displayName}")
                    host.restart(HomeScreen())
                }
                is CodeSignIn.Outcome.Failed -> failed(outcome.message)
            }
        }
    }

    private fun failed(message: String) {
        status.text = message
        qr.visibility = View.GONE
        again.visibility = View.VISIBLE
        again.requestFocus()
    }

    /** The page's address as a QR code, as big as the screen's width allows. */
    private fun showQr(link: String) {
        val size = (context.resources.displayMetrics.widthPixels - context.dp(32)).coerceIn(context.dp(96), context.dp(240))
        val bitmap = runCatching { qrBitmap(link, size) }.getOrNull() ?: return
        qr.setImageBitmap(bitmap)
        qr.visibility = View.VISIBLE
    }

    companion object {
        /** [text] as a QR code [size] pixels wide: dark on white, with a quiet border so cameras find it. */
        fun qrBitmap(text: String, size: Int): Bitmap {
            val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            // Whole pixels per module, so it's sharp.
            val scale = (size / matrix.width).coerceAtLeast(1)
            val width = matrix.width * scale
            val pixels = IntArray(width * width)
            for (y in 0 until width) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (matrix[x / scale, y / scale]) Color.BLACK else Color.WHITE
                }
            }
            return Bitmap.createBitmap(pixels, width, width, Bitmap.Config.RGB_565)
        }
    }
}
