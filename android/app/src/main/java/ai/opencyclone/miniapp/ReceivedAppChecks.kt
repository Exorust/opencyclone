package ai.opencyclone.miniapp

import ai.opencyclone.R
import androidx.annotation.StringRes

/**
 * What "Checked" means for an app file that someone sent (REBUILD-PLAN decision 11): one file, under the
 * size limit, a web page with a `<title>`, and no address outside the file, no network call, nothing that
 * runs text as code. Our own style block is removed first, so its font address does not count.
 *
 * ponytail: these are text checks. They catch every plain spelling of the constructs below, not code that
 * builds a name at run time (for example `'fe' + 'tch'` looked up through an alias). The runtime keeps
 * the real limits: [MiniAppActivity.respond] refuses every request except "/" and the font.
 */
object ReceivedAppChecks {
    const val MAX_BYTES = 2 * 1024 * 1024

    /** One reason a received file is not opened, in the order the card shows them. */
    enum class Finding(@StringRes val text: Int) {
        NOT_ONE_FILE(R.string.received_finding_not_one_file),
        FILE_TOO_LARGE(R.string.received_finding_file_too_large),
        NOT_TEXT(R.string.received_finding_not_text),
        NOT_HTML(R.string.received_finding_not_html),
        NO_TITLE(R.string.received_finding_no_title),
        APP_TOO_LARGE(R.string.received_finding_app_too_large),
        OUTSIDE_ADDRESS(R.string.received_finding_outside_address),
        NETWORK_CALL(R.string.received_finding_network_call),
        ASKS_INTERNET(R.string.received_finding_asks_internet),
        OPENS_WINDOW(R.string.received_finding_opens_window),
        EMBEDS(R.string.received_finding_embeds),
        BASE(R.string.received_finding_base),
        RUNS_TEXT_AS_CODE(R.string.received_finding_runs_text),
        SECOND_FILE(R.string.received_finding_second_file),
        SECRET(R.string.received_finding_secret),
        OTHER(R.string.received_finding_other),
        LOAD_FAILED(R.string.received_finding_load_failed),
        UNREADABLE(R.string.received_finding_unreadable),
        SAVE_FAILED(R.string.received_finding_save_failed),
    }

    private val ignoreCase = setOf(RegexOption.IGNORE_CASE)
    private val tag = Regex("""<([a-zA-Z][\w:-]*)\b[^>]*>""")
    private val attribute = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val urlAttributes = setOf("src", "href", "action", "formaction", "poster", "data", "background",
        "xlink:href", "ping", "srcset", "imagesrcset", "manifest", "codebase", "cite", "longdesc")
    private val embedTags = setOf("iframe", "frame", "frameset", "object", "embed", "applet", "portal")
    private val cssUrl = Regex("""url\(\s*(?:"([^"]*)"|'([^']*)'|([^)]*))\s*\)""", ignoreCase)
    private val cssImport = Regex("""@import\b""", ignoreCase)
    private val xmlns = Regex("""\bxmlns(?::[\w-]+)?\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""", ignoreCase)
    private val schemeAddress = Regex("""\b(?:https?|wss?|ftp):""", ignoreCase)
    private val protocolRelative = Regex("""["'`(]\s*(?://|\\\\)[^\s"'`/\\]""")
    private val networkApi = Regex("""\b(?:XMLHttpRequest|WebSocket|EventSource|importScripts|sendBeacon)\b""")
    private val fetchWord = Regex("""\bfetch\b""")
    private val bridgeFetch = Regex("""\bphone\s*\.\s*fetch\b""")
    private val openWindow = Regex("""\b(?:window|self|globalThis|top|parent)\s*\.\s*open\b""")
    private val textAsCode = Regex("""\beval\s*\(|\bFunction\s*\(""")
    // A lookup by computed name can reach any of the above without spelling it; refused as well.
    private val computedLookup = Regex("""\b(?:window|self|globalThis|top|parent|phone)\s*\[""")
    private val scheme = Regex("""^[a-zA-Z][\w+.-]*:""")

    /**
     * The findings for [html], empty when it passes. [byteCount] is the size of the received file and
     * [fileCount] the number of files that came with the intent.
     */
    fun check(html: String, byteCount: Int = html.toByteArray(Charsets.UTF_8).size, fileCount: Int = 1): List<Finding> {
        val found = mutableSetOf<Finding>()
        if (fileCount != 1) found += Finding.NOT_ONE_FILE
        if (byteCount > MAX_BYTES) found += Finding.FILE_TOO_LARGE
        val body = BuiltAppStyle.strip(html)

        tag.findAll(body).forEach { match ->
            val name = match.groupValues[1].lowercase()
            if (name in embedTags) found += Finding.EMBEDS
            if (name == "base") found += Finding.BASE
            val attrs = attributes(match.value)
            if (name == "meta" && attrs["http-equiv"].equals("refresh", true)) found += Finding.OUTSIDE_ADDRESS
            attrs.filterKeys { it in urlAttributes }.forEach { (key, value) ->
                val parts = if (key.endsWith("srcset")) value.split(',').map { it.trim().substringBefore(' ') } else listOf(value)
                parts.forEach { address(it)?.let(found::add) }
            }
        }
        cssUrl.findAll(body).forEach { m -> address(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty())?.let(found::add) }
        if (cssImport.containsMatchIn(body)) found += Finding.OUTSIDE_ADDRESS
        val text = xmlns.replace(body, "")
        if (schemeAddress.containsMatchIn(text) || protocolRelative.containsMatchIn(text)) found += Finding.OUTSIDE_ADDRESS

        if (networkApi.containsMatchIn(body)) found += Finding.NETWORK_CALL
        val fetches = fetchWord.findAll(body).count()
        val bridgeFetches = bridgeFetch.findAll(body).count()
        if (fetches > bridgeFetches) found += Finding.NETWORK_CALL
        if (bridgeFetches > 0) found += Finding.ASKS_INTERNET
        if (openWindow.containsMatchIn(body)) found += Finding.OPENS_WINDOW
        if (textAsCode.containsMatchIn(body) || computedLookup.containsMatchIn(body)) found += Finding.RUNS_TEXT_AS_CODE

        HtmlChecks.check(body).forEach { problem ->
            when (problem) {
                HtmlChecks.NO_ACCENT -> Unit // our default accent applies
                HtmlChecks.NO_HTML -> found += Finding.NOT_HTML
                HtmlChecks.NO_TITLE -> found += Finding.NO_TITLE
                HtmlChecks.TOO_LARGE -> found += Finding.APP_TOO_LARGE
                HtmlChecks.SECRET -> found += Finding.SECRET
                // The attribute scan above already says why; this only adds a reason when it found none.
                HtmlChecks.EXTERNAL_RESOURCE -> if (Finding.OUTSIDE_ADDRESS !in found) found += Finding.SECOND_FILE
                else -> found += Finding.OTHER
            }
        }
        return found.sortedBy { it.ordinal }
    }

    /** null when [value] stays inside the file, otherwise the finding it causes. */
    private fun address(raw: String): Finding? {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("#") || value == BuiltAppStyle.FONT_PATH) return null
        // Character references can spell any address; the browser decodes them, so we refuse them here.
        if (value.contains("&#") || value.contains("&colon;", true)) return Finding.OUTSIDE_ADDRESS
        if (value.startsWith("data:", true) || value.startsWith("javascript:", true)) return null
        if (value.startsWith("//") || value.startsWith("\\") || scheme.containsMatchIn(value)) return Finding.OUTSIDE_ADDRESS
        return Finding.SECOND_FILE
    }

    private fun attributes(tagText: String): Map<String, String> = attribute.findAll(tagText).associate {
        it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty()
    }

    /** The app's name: its title, or null when it has none. */
    fun name(html: String): String? = HtmlChecks.name(BuiltAppStyle.strip(html))?.replace(Regex("""\s+"""), " ")?.take(MAX_NAME)

    /** [name], or "[name] 2", "[name] 3"… so that it differs from every name in [existing] (ignoring case). */
    fun uniqueName(name: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var n = 2
        while ("$name $n".lowercase() in taken) n++
        return "$name $n"
    }

    private const val MAX_NAME = 60
}
