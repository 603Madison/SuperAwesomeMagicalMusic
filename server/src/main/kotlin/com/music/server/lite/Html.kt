package com.music.server.lite

import io.ktor.http.encodeURLParameter

/**
 * A small HTML builder for the lite player's pages. Text and attribute values are always escaped;
 * the only way to put markup in as it is, [raw], is kept for constants written here.
 */
@DslMarker
annotation class HtmlDsl

@HtmlDsl
class Html {
    private val out = StringBuilder(4096)

    /** `<tag attrs>…</tag>`. An attribute that's null or false is left out; true is written bare. */
    fun el(tag: String, vararg attrs: Pair<String, Any?>, body: Html.() -> Unit = {}) {
        open(tag, attrs)
        body()
        out.append("</").append(tag).append('>')
    }

    /** An element with nothing inside and no end tag (`<img>`, `<input>`, `<meta>`). */
    fun empty(tag: String, vararg attrs: Pair<String, Any?>) = open(tag, attrs)

    /** Text, escaped. */
    fun text(value: Any?) {
        if (value != null) escapeTo(out, value.toString())
    }

    operator fun String?.unaryPlus() = text(this)

    /** Markup as it is: only for constants. */
    fun raw(markup: String) {
        out.append(markup)
    }

    private fun open(tag: String, attrs: Array<out Pair<String, Any?>>) {
        out.append('<').append(tag)
        for ((name, value) in attrs) {
            when (value) {
                null, false -> Unit
                true -> out.append(' ').append(name)
                else -> {
                    out.append(' ').append(name).append("=\"")
                    escapeTo(out, value.toString())
                    out.append('"')
                }
            }
        }
        out.append('>')
    }

    // Shorthands for what the pages use most.
    fun h1(value: String) = el("h1") { text(value) }
    fun h2(value: String) = el("h2") { text(value) }
    fun p(value: String, cls: String? = null) = el("p", "class" to cls) { text(value) }
    fun a(href: String, value: String, cls: String? = null) = el("a", "href" to href, "class" to cls) { text(value) }
    fun hidden(name: String, value: String) = empty("input", "type" to "hidden", "name" to name, "value" to value)

    override fun toString() = out.toString()

    companion object {
        fun escape(value: String): String = StringBuilder(value.length + 16).also { escapeTo(it, value) }.toString()

        private fun escapeTo(out: StringBuilder, value: String) {
            for (c in value) {
                when (c) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> out.append("&quot;")
                    '\'' -> out.append("&#39;")
                    else -> out.append(c)
                }
            }
        }
    }
}

/** [path] with [params] (nulls left out), each encoded. */
fun url(path: String, vararg params: Pair<String, Any?>): String {
    val present = params.filter { it.second != null }
    if (present.isEmpty()) return path
    return path + "?" + present.joinToString("&") { (k, v) -> "$k=${v.toString().encodeURLParameter()}" }
}
