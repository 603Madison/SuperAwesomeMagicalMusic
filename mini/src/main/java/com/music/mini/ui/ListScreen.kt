package com.music.mini.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.music.mini.R
import com.music.mini.net.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A row of a list. */
sealed class Row {
    /** Something to choose: OK (or a tap) runs [onClick]; [options] are its soft-key menu. */
    class Item(val title: String, val detail: String? = null, val options: (() -> List<Option>)? = null, val onClick: () -> Unit) : Row()

    /** A heading between parts of a list; never focused. */
    class Header(val title: String) : Row()

    /** A message ("Loading…", "Nothing here yet"); focusable so focus is always somewhere, and OK runs [onClick] if there is one. */
    class Note(val text: String, val onClick: (() -> Unit)? = null) : Row()

    val focusable: Boolean get() = this !is Header
}

/** One choice in an options menu. */
class Option(val label: String, val action: () -> Unit)

/**
 * A list to move through with the D-pad: up and down move the highlight (round from the end to
 * the start), OK chooses. Menus ([numbered]) show numbers and take 1–9 as shortcuts.
 */
abstract class ListScreen : Screen() {
    var rows: List<Row> = emptyList()
        private set

    /** Menus: numbered rows, and the number keys choose them. */
    open val numbered: Boolean = false

    /** Options for the whole screen (the soft key's menu, after the focused row's own). */
    open fun screenOptions(): List<Option> = emptyList()

    /** Whether the soft key offers options here at all. */
    open val hasOptions: Boolean = false

    override val optionsLabel: String? get() = if (hasOptions) "Options" else null

    lateinit var list: RecyclerView
        private set
    private val adapter = RowAdapter()
    private val layout by lazy { FocusingLayoutManager(context) }
    private var remembered = -1

    override fun create(context: Context): View {
        list = RecyclerView(context).apply {
            layoutManager = layout
            adapter = this@ListScreen.adapter
            itemAnimator = null
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            clipToPadding = false
        }
        return wrap(list)
    }

    /** The screen around the list (a search box above it, say). */
    protected open fun wrap(list: RecyclerView): View = list

    override fun defaultFocus(): View? = if (::list.isInitialized) list else null

    /** Shows [rows], with the highlight on row [focus] (the first that can have it, by default) unless not [moveFocus]. */
    fun setRows(rows: List<Row>, focus: Int? = null, moveFocus: Boolean = true) {
        this.rows = rows
        adapter.notifyDataSetChanged()
        val wanted = focus ?: firstFocusable()
        if (moveFocus && wanted >= 0) focusAt(wanted)
    }

    /** Changes one row in place, leaving the highlight where it is. */
    fun updateRow(index: Int, row: Row) {
        if (index !in rows.indices) return
        rows = rows.toMutableList().also { it[index] = row }
        adapter.notifyItemChanged(index)
    }

    /** Moves the highlight to row [position] (or the nearest that can have it), scrolling to it. */
    fun focusAt(position: Int) {
        if (rows.isEmpty()) {
            list.requestFocus()
            return
        }
        val target = nearestFocusable(position.coerceIn(0, rows.lastIndex)).takeIf { it >= 0 } ?: return
        val holder = list.findViewHolderForAdapterPosition(target)
        if (holder != null && !list.isLayoutRequested) {
            // On screen already (taking focus scrolls it fully into view).
            layout.pendingFocus = -1
            holder.itemView.requestFocus()
        } else {
            // Not laid out yet: once it is.
            layout.pendingFocus = target
            list.scrollToPosition(target)
        }
    }

    /** The row with the highlight, or -1. */
    fun focusedIndex(): Int {
        val focused = list.findFocus() ?: return -1
        if (focused === list) return -1
        return list.findContainingViewHolder(focused)?.bindingAdapterPosition ?: -1
    }

    fun focusedRow(): Row? = focusedIndex().takeIf { it >= 0 }?.let { rows.getOrNull(it) }

    /** The row the highlight starts on. */
    open fun initialFocus(): Int = firstFocusable()

    override fun onShown(first: Boolean) {
        focusAt(if (first || remembered < 0) initialFocus() else remembered)
    }

    override fun onHidden() {
        remembered = focusedIndex()
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (numbered && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val items = rows.withIndex().filter { it.value is Row.Item }
            val chosen = items.getOrNull(keyCode - KeyEvent.KEYCODE_1) ?: return true
            focusAt(chosen.index)
            (chosen.value as Row.Item).onClick()
            return true
        }
        val here = focusedIndex()
        if (here < 0) return false
        // Round from one end to the other: long menus are quicker to get through.
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && here == lastFocusable() && here != firstFocusable()) {
            focusAt(firstFocusable())
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && here == firstFocusable() && wrapsUp()) {
            focusAt(lastFocusable())
            return true
        }
        return false
    }

    /** Whether up from the first row goes round to the last (not where something sits above the list). */
    protected open fun wrapsUp(): Boolean = lastFocusable() != firstFocusable()

    override fun onOptions() {
        val row = focusedRow() as? Row.Item
        val options = row?.options?.invoke().orEmpty() + screenOptions()
        if (options.isNotEmpty()) host.push(OptionsScreen(row?.title ?: title, options))
    }

    /** Loads rows with [block], showing "Loading…" meanwhile and what went wrong (with Try again) if it fails. */
    protected fun load(loading: String = "Loading…", block: suspend () -> List<Row>) {
        setRows(listOf(Row.Note(loading)))
        scope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf(Row.Note("${problem(e)}\nPress OK to try again.") { load(loading, block) })
            }
            setRows(result)
        }
    }

    protected fun firstFocusable(): Int = rows.indexOfFirst { it.focusable }

    protected fun lastFocusable(): Int = rows.indexOfLast { it.focusable }

    private fun nearestFocusable(position: Int): Int {
        if (rows.getOrNull(position)?.focusable == true) return position
        val after = (position until rows.size).firstOrNull { rows[it].focusable }
        return after ?: (position downTo 0).firstOrNull { rows[it].focusable } ?: -1
    }

    /** Puts focus on its row once the list has been laid out. */
    private class FocusingLayoutManager(context: Context) : LinearLayoutManager(context) {
        var pendingFocus = -1

        override fun onLayoutCompleted(state: RecyclerView.State?) {
            super.onLayoutCompleted(state)
            val position = pendingFocus
            if (position < 0) return
            val view = findViewByPosition(position) ?: return
            pendingFocus = -1
            view.post { view.requestFocus() }
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        inner class Holder(val root: LinearLayout, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val title = TextView(c).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val detail = TextView(c).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(title)
                addView(detail)
            }
            if (viewType == 1) {
                title.setTextColor(c.color(R.color.accent))
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                title.setTypeface(title.typeface, Typeface.BOLD)
                root.setPadding(c.dp(8), c.dp(10), c.dp(8), c.dp(2))
                detail.visibility = View.GONE
            } else {
                title.setTextColor(c.getColorStateList(R.color.row_text))
                detail.setTextColor(c.getColorStateList(R.color.row_detail))
                // The row's state (focused) reaches its texts, so they change colour with it.
                title.isDuplicateParentStateEnabled = true
                detail.isDuplicateParentStateEnabled = true
                root.setBackgroundResource(R.drawable.row_background)
                root.minimumHeight = c.dp(44)
                root.setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
                root.isFocusable = true
                root.isClickable = true
            }
            return Holder(root, title, detail)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> holder.title.text = row.title
                is Row.Item -> {
                    val number = rows.subList(0, position).count { it is Row.Item } + 1
                    holder.title.text = if (numbered && number <= 9) "$number  ${row.title}" else row.title
                    holder.detail.text = row.detail.orEmpty()
                    holder.detail.visibility = if (row.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
                    holder.root.setOnClickListener { row.onClick() }
                    holder.root.setOnLongClickListener { if (row.options != null) { onOptionsOf(row); true } else false }
                }
                is Row.Note -> {
                    holder.title.text = row.text
                    holder.detail.visibility = View.GONE
                    holder.root.setOnClickListener { row.onClick?.invoke() }
                    holder.root.setOnLongClickListener(null)
                }
            }
        }
    }

    private fun onOptionsOf(row: Row.Item) {
        val options = row.options?.invoke().orEmpty() + screenOptions()
        if (options.isNotEmpty()) host.push(OptionsScreen(row.title, options))
    }
}

/** A short menu (the soft key's options, or a choice): numbered; choosing closes it and does it. */
class OptionsScreen(override val title: String, private val options: List<Option>) : ListScreen() {
    override val numbered = true

    override fun onShown(first: Boolean) {
        // Choosing closes the menu first, so what's chosen can open a screen of its own.
        if (first) setRows(options.map { option -> Row.Item(option.label) { host.pop(); option.action() } })
        super.onShown(first)
    }
}

/** What went wrong, in words for people. */
fun problem(e: Throwable): String = when (e) {
    is ServerException -> when (e.code) {
        401 -> "You've been signed out. Sign in again in Settings."
        else -> e.message ?: "The server couldn't do that"
    }
    is java.net.UnknownHostException -> "Can't find the server. Check the connection."
    is java.net.ConnectException, is java.net.SocketTimeoutException -> "Can't reach the server right now."
    is java.io.IOException -> "Can't reach the server (${e.message ?: "no connection"})."
    else -> e.message ?: "Something went wrong"
}
