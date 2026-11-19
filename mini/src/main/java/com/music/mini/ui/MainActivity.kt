package com.music.mini.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.music.mini.Mini
import com.music.mini.R
import com.music.mini.mini
import com.music.mini.playback.ControllerPlayer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The one activity: a title at the top, the screen in the middle, and the soft keys' labels at
 * the bottom, as on a feature phone. Screens stack up; Back (or the right soft key) closes the
 * top one. Everything works from the keys alone: the D-pad moves the highlight, OK chooses, the
 * left soft key (or Menu) opens the screen's options.
 */
class MainActivity : Activity(), Host {
    override val context: Context get() = this
    override val mini: Mini get() = applicationContext.mini

    private val stack = ArrayList<Screen>()
    private val scope = MainScope()
    private lateinit var titleView: TextView
    private lateinit var playingView: TextView
    private lateinit var content: FrameLayout
    private lateinit var softLeft: TextView
    private lateinit var softCentre: TextView
    private lateinit var softRight: TextView
    private var backCallback: Any? = null

    val top: Screen? get() = stack.lastOrNull()

    /** The view with the highlight, if any. */
    val focusedView: View? get() = if (::content.isInitialized) content.findFocus() else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(chrome())
        restart(if (mini.prefs.signedIn) HomeScreen() else ServerScreen())
        if (mini.prefs.signedIn) (mini.player as? ControllerPlayer)?.connect()
        scope.launch {
            mini.player.state.collect { state -> playingView.text = if (state.playing) "♪" else "" }
        }
        // Android 13 and later hand Back to a callback rather than as a key.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { pop() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        stack.forEach { it.onClosed() }
        stack.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun chrome(): View {
        val c = this
        titleView = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        playingView = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(color(R.color.accent))
            contentDescription = "Playing"
        }
        val titleBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.surface))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(playingView)
        }
        content = FrameLayout(c)
        fun softKey(gravity: Int) = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            this.gravity = gravity or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 1
            // Tappable on phones with a touch screen, but never in the D-pad's way.
            isFocusable = false
        }
        softLeft = softKey(Gravity.START).apply { setOnClickListener { this@MainActivity.top?.onOptions() } }
        softCentre = softKey(Gravity.CENTER).apply { setTextColor(color(R.color.muted)) }
        softRight = softKey(Gravity.END).apply { setOnClickListener { pop() } }
        val softKeys = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.surface))
            addView(softLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(softCentre, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(softRight, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.background))
            addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(softKeys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---- the stack of screens ----

    override fun push(screen: Screen) {
        top?.onHidden()
        screen.host = this
        stack += screen
        show(screen, first = true)
    }

    override fun pop() {
        if (stack.size <= 1) {
            // The first screen: off to the background, the music playing on.
            moveTaskToBack(true)
            return
        }
        val closing = stack.removeAt(stack.lastIndex)
        closing.onHidden()
        closing.onClosed()
        show(stack.last(), first = false)
    }

    override fun replace(screen: Screen) {
        stack.removeLastOrNull()?.let { it.onHidden(); it.onClosed() }
        screen.host = this
        stack += screen
        show(screen, first = true)
    }

    override fun restart(screen: Screen) {
        stack.forEach { it.onClosed() }
        stack.clear()
        push(screen)
    }

    private fun show(screen: Screen, first: Boolean) {
        val view = screen.view()
        (view.parent as? ViewGroup)?.removeView(view)
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        refreshChrome()
        screen.onShown(first)
        // The on-screen keyboard only stays up for a text box.
        if (focusedView !is EditText) hideKeyboard()
    }

    override fun refreshChrome() {
        val screen = top ?: return
        titleView.text = screen.title
        softLeft.text = screen.optionsLabel.orEmpty()
        softCentre.text = screen.centreLabel
        softRight.text = if (stack.size > 1) "Back" else "Exit"
    }

    fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(content.windowToken, 0)
    }

    // ---- keys ----

    // Back as a key is for Android 12 and older (and flip phones' Back keys there); newer ones
    // use the callback registered in onCreate.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val screen = top
        when (code) {
            // Soft keys and Back act as the key comes up (as Android's own Back does).
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    if (code == KeyEvent.KEYCODE_SOFT_LEFT || code == KeyEvent.KEYCODE_MENU) screen?.onOptions() else pop()
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && screen?.onKey(code, event) == true) return true
        if (super.dispatchKeyEvent(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // Not taken by the views: the highlight moves (as Android does it, so it's the same in tests).
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> 0
            else -> return false
        }
        val focused = focusedView?.takeIf { it.isShown && isInside(it) }
        if (focused == null) {
            // Nothing has the highlight (after a touch, say): it goes where the screen wants it.
            screen?.onShown(first = false)
            return true
        }
        if (direction != 0) focused.focusSearch(direction)?.takeIf { isInside(it) }?.requestFocus(direction)
        return true
    }

    private fun isInside(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === content) return true
            parent = parent.parent
        }
        return false
    }
}
