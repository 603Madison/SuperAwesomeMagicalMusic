package com.music.mini.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import com.music.mini.Mini
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** What a screen can ask of the app around it (the activity). */
interface Host {
    val context: Context
    val mini: Mini

    fun push(screen: Screen)

    /** Back one screen; at the first, the app goes to the background (the music keeps playing). */
    fun pop()

    /** [screen] instead of the one showing. */
    fun replace(screen: Screen)

    /** [screen] as the only one (after signing in or out). */
    fun restart(screen: Screen)

    /** The title and soft-key labels changed. */
    fun refreshChrome()
}

/**
 * One screen of the app. Each makes its view once and keeps it while it's in the stack, so going
 * back finds it as it was, with focus where it was. Keys reach [onKey] before the views; the soft
 * keys are the activity's: the left one is [optionsLabel] (if any), the right one is Back.
 */
abstract class Screen {
    lateinit var host: Host
        internal set
    val scope: CoroutineScope = MainScope()
    val context: Context get() = host.context
    val mini: Mini get() = host.mini

    abstract val title: String

    /** The left soft key (and Menu key): this screen's options, if it has any. */
    open val optionsLabel: String? = null

    /** What OK does here, shown between the soft keys. */
    open val centreLabel: String = "Select"

    private var view: View? = null
    private var lastFocus: View? = null

    fun view(): View = view ?: create(context).also { view = it }

    protected abstract fun create(context: Context): View

    /** Where focus goes when nothing else has it. */
    open fun defaultFocus(): View? = view

    /** Shown: the first time ([first]), or again after the screen above it closed. */
    open fun onShown(first: Boolean) {
        val again = lastFocus?.takeIf { !first && it.isAttachedToWindow && it.isShown }
        (again ?: defaultFocus())?.requestFocus()
    }

    open fun onHidden() {
        lastFocus = view?.findFocus()
    }

    /** A key going down, before the views see it; true if it's been dealt with. */
    open fun onKey(keyCode: Int, event: KeyEvent): Boolean = false

    open fun onOptions() {}

    open fun onClosed() {
        scope.cancel()
    }
}
