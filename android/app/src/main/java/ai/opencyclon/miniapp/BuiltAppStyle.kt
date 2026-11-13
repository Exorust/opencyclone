package ai.opencyclon.miniapp

/**
 * The fixed look of every built app (UI.md 5.9). [Builder] inserts [CSS] into `<head>` after each
 * generation, so the model never copies it. The app's one accent colour comes from
 * `<meta name="accent" content="#RRGGBB">` in the file.
 */
object BuiltAppStyle {
    const val DEFAULT_ACCENT = "#4733D6"
    const val FONT_PATH = "/inter.woff2"
    const val STYLE_ID = "built-app-style"

    /** The style rule. `--accent` here is the default; [apply] sets the declared one after it. */
    const val CSS = """@font-face{font-family:Inter;src:url($FONT_PATH) format("woff2");font-weight:100 900;font-style:normal;font-display:swap}
:root{--accent:$DEFAULT_ACCENT;--ink:#15173A;--ink-2:#5B5E7A;--surface-2:#F4F4FB;--chip:rgba(255,255,255,.22);color-scheme:light}
*{box-sizing:border-box}
html{background:#FFFFFF}
body{margin:0;padding:16px 20px 32px;background:#FFFFFF;color:var(--ink);font-family:Inter,system-ui,Roboto,sans-serif;font-size:17px;line-height:1.25;letter-spacing:-0.01em;-webkit-font-smoothing:antialiased}
h1,h2,h3,p{margin:0}
button{font:inherit;color:inherit;background:none;border:0;padding:0;cursor:pointer}
.top{display:flex;align-items:center;justify-content:space-between;gap:8px;min-height:32px;font-size:13px;color:var(--ink-2)}
.top .name{font-weight:600;color:var(--ink)}
.tabs{display:flex;gap:4px}
.tab{height:28px;padding:0 12px;border-radius:14px;font-size:13px;color:var(--ink-2);display:inline-flex;align-items:center}
.tab.on{background:var(--surface-2);color:var(--ink);font-weight:600}
.display{margin:20px 0 20px;font-size:40px;font-weight:500;letter-spacing:-0.04em;line-height:1.1}
.small{font-size:13px;color:var(--ink-2)}
.progress,.card{background:var(--surface-2);border-radius:20px;padding:16px;margin:0 0 12px}
.accent-card{background:var(--accent);color:#FFFFFF;border-radius:20px;padding:16px;margin:0 0 24px}
.accent-card .label,.accent-card .line{font-size:13px;color:rgba(255,255,255,.88)}
.accent-card .label{display:flex;justify-content:space-between;gap:8px}
.accent-card .title{margin:6px 0 4px;font-size:24px;font-weight:600;letter-spacing:-0.02em;line-height:1.1;color:#FFFFFF}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.chip{display:inline-flex;align-items:center;height:32px;padding:0 14px;border-radius:16px;background:var(--chip);color:#FFFFFF;font-size:15px}
.section{display:flex;align-items:baseline;justify-content:space-between;margin:8px 0 12px}
.section h2{font-size:22px;font-weight:500;letter-spacing:-0.02em}
.section .count{font-size:15px;color:var(--ink-2)}
.row{display:flex;align-items:center;gap:14px;width:100%;height:68px;padding:0 16px;margin:0 0 10px;border-radius:16px;background:var(--surface-2);text-align:left}
.row .glyph{flex:none;display:flex;align-items:center;justify-content:center;width:36px;height:36px;border-radius:10px;background:#ECEBFB;color:var(--accent)}
.row .glyph svg{width:18px;height:18px}
.row .text{flex:1;min-width:0}
.row .t{font-size:17px;font-weight:500}
.row .s{font-size:13px;color:var(--ink-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.row .chev{flex:none;color:var(--ink-2);font-size:20px}
.link{display:block;margin:28px auto 0;text-align:center;font-size:13px;color:var(--ink);text-decoration:none}
"""

    private val styleBlock = Regex("""\s*<style\b[^>]*\bid\s*=\s*["']?$STYLE_ID["']?[^>]*>.*?</style\s*>\s*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val head = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val meta = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val hex = Regex("""#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})""")

    /** The declared accent as `#RRGGBB`, or null when the meta is absent or its value is not a hex colour. */
    fun declaredAccent(html: String): String? = meta.findAll(html).firstNotNullOfOrNull { tag ->
        val attrs = attribute.findAll(tag.value).associate {
            it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty()
        }
        if (!attrs["name"].equals("accent", true)) return@firstNotNullOfOrNull null
        val value = hex.matchEntire(attrs["content"].orEmpty().trim())?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
        "#" + (if (value.length == 3) value.map { "$it$it" }.joinToString("") else value).uppercase()
    }

    /** The declared accent, or [DEFAULT_ACCENT] when absent or malformed. */
    fun accentOf(html: String): String = declaredAccent(html) ?: DEFAULT_ACCENT

    /** [html] without the inserted style block. Used before a file goes back to the model. */
    fun strip(html: String): String = styleBlock.replace(html, "")

    /** [html] with exactly one current style block at the start of `<head>`. Idempotent. */
    fun apply(html: String): String {
        val clean = strip(html)
        val block = "<style id=\"$STYLE_ID\">\n$CSS:root{--accent:${accentOf(clean)}}\n</style>\n"
        head.find(clean)?.let { return clean.substring(0, it.range.last + 1) + "\n" + block + clean.substring(it.range.last + 1) }
        htmlTag.find(clean)?.let { return clean.substring(0, it.range.last + 1) + "<head>\n$block</head>" + clean.substring(it.range.last + 1) }
        return block + clean
    }
}
