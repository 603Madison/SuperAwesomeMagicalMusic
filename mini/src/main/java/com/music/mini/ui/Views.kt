package com.music.mini.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.music.mini.R

// Small helpers for building screens in code: plain views, big text, an obvious focus.

fun Context.dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun Context.color(id: Int): Int = getColor(id)

/** Text that's read, not chosen. */
fun Context.label(text: CharSequence, sizeSp: Float = 16f, bold: Boolean = false, muted: Boolean = false): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color(if (muted) R.color.muted else R.color.text))
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(8), dp(4), dp(8), dp(4))
}

/** Something to choose, the same as a list row: OK (or a tap) runs [onClick]. */
fun Context.button(text: CharSequence, onClick: () -> Unit): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(getColorStateList(R.color.row_text))
    setBackgroundResource(R.drawable.row_background)
    gravity = Gravity.CENTER
    minHeight = dp(44)
    setPadding(dp(12), dp(8), dp(12), dp(8))
    isFocusable = true
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.column(vararg children: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    children.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
}

/** "3:07", or "1:02:03" for long ones. */
fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
