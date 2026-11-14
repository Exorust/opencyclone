package ai.opencyclone.widgets

import ai.opencyclone.miniapp.BuiltAppStyle
import ai.opencyclone.miniapp.HtmlChecks
import org.json.JSONObject

/**
 * The phone's part of every widget file: a fixed style block and the `phone` script (both inserted into `<head>`,
 * never written by the model), the declared sources (`<meta name="sources">`), and the widget checks.
 */
object WidgetHtml {
    const val MAX_BYTES = 60 * 1024
    const val STYLE_ID = "widget-style"
    const val SHIM_ID = "widget-phone"
    /** The JavaScript interface name of [WidgetBridge]; pages use `window.phone`, which the shim defines. */
    const val NATIVE = "widgetNative"

    const val CSS = """@font-face{font-family:Inter;src:url(${BuiltAppStyle.FONT_PATH}) format("woff2");font-weight:100 900;font-style:normal;font-display:swap}
:root{--accent:${BuiltAppStyle.DEFAULT_ACCENT};--ink:#15173A;--ink-2:#5B5E7A;--ink-3:#8A8DA8;--surface-2:#F4F4FB;--pale:#ECEBFB;color-scheme:light}
*{box-sizing:border-box}
html{height:100%;overflow:hidden;background:#FFFFFF}
body{display:flow-root;background:#FFFFFF}
body{margin:0;padding:14px 16px;color:var(--ink);font-family:Inter,system-ui,Roboto,sans-serif;font-size:15px;line-height:1.3;letter-spacing:-0.01em;-webkit-font-smoothing:antialiased;-webkit-user-select:none;user-select:none}
h1,h2,h3,p{margin:0}
button{font:inherit;color:inherit;background:none;border:0;padding:0;cursor:pointer}
.label{font-size:13px;color:var(--ink-2)}
.big{color:#15173A!important;font-size:34px;font-weight:600;letter-spacing:-0.03em;line-height:1.1}
.value{font-size:22px;font-weight:500;letter-spacing:-0.02em}
.small{font-size:13px;color:var(--ink-2)}
.row{display:flex;align-items:baseline;justify-content:space-between;gap:8px;margin:6px 0}
.stack{display:flex;flex-direction:column;gap:6px}
.dash{color:var(--ink-3)}
.accent{color:var(--accent)}
.bar{height:8px;border-radius:4px;background:var(--surface-2);overflow:hidden}
.bar>i{display:block;height:100%;background:var(--accent);border-radius:4px}
.circles{display:flex;gap:6px}
.circles>i{width:18px;height:18px;border-radius:50%;border:1.5px solid #D6D6D2}
.circles>i.on{background:var(--accent);border-color:var(--accent)}
.ask{display:inline-flex;align-items:center;height:40px;padding:0 18px;border-radius:20px;background:var(--accent);color:#FFFFFF;font-weight:500}
"""

    /** Defines `phone.read(id)` (a promise of JSON or null), `phone.ask(text)`, and `phone.onrefresh`. */
    const val SHIM = """(function(){var n=window.$NATIVE;var w={};var c=0;
window.__widgetResult=function(id,t){var f=w[id];delete w[id];if(!f)return;var v=null;try{v=t==null?null:JSON.parse(t)}catch(e){v=null}f(v)};
window.phone={read:function(s){return new Promise(function(r){var id=String(++c);w[id]=r;n.read(String(s),id)})},ask:function(t){return n.ask(String(t))},onrefresh:null};
window.addEventListener('error',function(e){n.failed(String(e.message||'error'))});
window.addEventListener('unhandledrejection',function(e){n.failed(String(e.reason||'error'))});
})();"""

    private val ownBlocks = Regex("""\s*<(style|script)\b[^>]*\bid\s*=\s*["']?($STYLE_ID|$SHIM_ID)["']?[^>]*>.*?</\1\s*>\s*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val head = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val meta = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val address = Regex("""(?:https?:|wss?:|["'(]\s*)//[a-z0-9.-]+\.[a-z]{2,}""", RegexOption.IGNORE_CASE)
    private const val SVG_NS = "//www.w3.org"

    /** [html] without the phone's inserted blocks; sent back to the model. */
    fun strip(html: String): String = ownBlocks.replace(html, "")

    /** [html] with exactly one style block and one `phone` script at the start of `<head>`. Idempotent. */
    fun apply(html: String): String {
        val clean = strip(html)
        val block = "<style id=\"$STYLE_ID\">\n$CSS:root{--accent:${BuiltAppStyle.accentOf(clean)}}\n</style>\n" +
            "<script id=\"$SHIM_ID\">$SHIM</script>\n"
        head.find(clean)?.let { return clean.substring(0, it.range.last + 1) + "\n" + block + clean.substring(it.range.last + 1) }
        htmlTag.find(clean)?.let { return clean.substring(0, it.range.last + 1) + "<head>\n$block</head>" + clean.substring(it.range.last + 1) }
        return block + clean
    }

    private fun metaTag(html: String, name: String): Pair<MatchResult, String>? = meta.findAll(html).firstNotNullOfOrNull { tag ->
        val attrs = attribute.findAll(tag.value).associate {
            it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty()
        }
        if (attrs["name"].equals(name, true)) tag to attrs["content"].orEmpty() else null
    }

    /** Standards mode keeps root viewport metrics and natural body height consistent, even without a doctype. */
    fun forDisplay(html: String): String {
        var clean = html.replace(Regex("""<!doctype[^>]*>""", RegexOption.IGNORE_CASE), "")
        while (true) {
            val viewport = metaTag(clean, "viewport") ?: break
            clean = clean.removeRange(viewport.first.range)
        }
        val viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        val match = head.find(clean)
        val document = if (match != null) clean.substring(0, match.range.last + 1) + viewport + clean.substring(match.range.last + 1)
            else viewport + clean
        return "<!doctype html>" + document
    }

    /** The sources the file declares in `<meta name="sources" content="history,app:3">`. */
    fun declaredSources(html: String): List<String> = metaTag(html, "sources")?.second?.let(WidgetEntity::splitSources) ?: emptyList()

    /** [html] with its sources meta set to [sources] (added to `<head>` when absent). */
    fun withSources(html: String, sources: List<String>): String {
        val tag = "<meta name=\"sources\" content=\"${sources.joinToString(",")}\">"
        metaTag(html, "sources")?.let { (match, _) -> return html.replaceRange(match.range, tag) }
        head.find(html)?.let { return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1) }
        htmlTag.find(html)?.let { return html.substring(0, it.range.last + 1) + "<head>$tag</head>" + html.substring(it.range.last + 1) }
        return tag + html
    }

    /** Fixed tool arguments chosen at build time: `<meta name="tool-args" content='{"tool:notes/list":{"limit":5}}'>`. */
    fun toolArgs(html: String, source: String): String = try {
        metaTag(html, "tool-args")?.second?.let { JSONObject(it).optJSONObject(source)?.toString() } ?: "{}"
    } catch (_: Exception) {
        "{}"
    }

    data class Checked(val html: String, val problems: List<String>, val removedSources: List<String>)

    /**
     * The widget checks: the built-app checks without the title rule, at most [MAX_BYTES], no outside addresses,
     * and declared sources within [allowed] (extra ones are removed from the meta and reported, not a failure).
     */
    fun check(html: String, allowed: List<String>): Checked {
        val model = strip(html)
        val problems = (HtmlChecks.check(model) - HtmlChecks.NO_TITLE - HtmlChecks.TOO_LARGE).toMutableList()
        if (model.toByteArray(Charsets.UTF_8).size > MAX_BYTES) problems += TOO_LARGE
        if (Regex("""text-transform\s*:\s*uppercase\b""", RegexOption.IGNORE_CASE).containsMatchIn(model)) problems += CAPITAL_LABELS
        if (address.findAll(model).any { !it.value.contains(SVG_NS) }) problems += OUTSIDE_ADDRESS
        val permitted = allowed + WidgetSources.CLOCK
        val declared = declaredSources(html)
        val removed = declared.filter { WidgetSources.sourceOf(it) !in permitted }
        val fixed = if (removed.isEmpty()) html else withSources(html, declared - removed.toSet())
        return Checked(fixed, problems.distinct(), removed)
    }

    const val CAPITAL_LABELS = "Labels must not be in capital letters."
    const val TOO_LARGE = "The widget file is larger than 60 KB"
    const val OUTSIDE_ADDRESS = "Remove every outside address; read data only with phone.read"
}
