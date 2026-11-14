package ai.opencyclone.action

import android.content.Intent
import java.util.Locale

/** What the executor found for an open_link before anything starts (F18.4). */
sealed class LinkCheck {
    /** The link opens in [packageName] ([label] is its name). */
    data class Opens(val packageName: String, val label: String) : LinkCheck()
    /** Nothing starts; [feedback] goes to the model as the step's result. */
    data class Refused(val feedback: String) : LinkCheck()
    /** The app that would open the link is on the protected list. */
    data class Blocked(val packageName: String) : LinkCheck()

    companion object {
        const val NO_APP = "No app on this phone opens that link."
        const val NO_CALLS = "I cannot place calls. Open the dialer instead."
        const val REFUSED_SCHEME = "I do not open file, content, or script links."
        const val CALL_NEEDS_TEL = "Only a tel: link can place a call."
        const val NOT_A_LINK = "That is not a link I can open."
        const val CALL_THROUGH_TEL = "Use open_link with call set to true and a tel: link to place a call."

        val REFUSED_SCHEMES = setOf("file", "content", "javascript", "data")
        private val CALL_ACTIONS = setOf(Intent.ACTION_CALL, "android.intent.action.CALL_PRIVILEGED",
            "android.intent.action.CALL_EMERGENCY")

        /** The refusal for a link before it is resolved, or null when it may be resolved. Pure. */
        fun refusal(uri: String, call: Boolean, canCall: Boolean): String? {
            val scheme = uri.substringBefore(':', "").trim().lowercase(Locale.ROOT)
            return when {
                scheme.isEmpty() || !scheme.all { it.isLetterOrDigit() || it in "+-." } -> NOT_A_LINK
                scheme in REFUSED_SCHEMES -> REFUSED_SCHEME
                call && scheme != "tel" -> CALL_NEEDS_TEL
                call && !canCall -> NO_CALLS
                else -> null
            }
        }

        /** The intent of a link: ACTION_CALL for a call, the parsed intent for `intent:`, else ACTION_VIEW. */
        fun intentFor(uri: String, call: Boolean): Intent {
            val parsed = android.net.Uri.parse(uri)
            if (call) return Intent(Intent.ACTION_CALL, parsed)
            if (uri.startsWith("intent:", ignoreCase = true)) {
                val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                // A call only through call=true; no selector that could reach another component.
                if (intent.action in CALL_ACTIONS) throw IllegalArgumentException(CALL_THROUGH_TEL)
                intent.selector = null
                return intent
            }
            return Intent(Intent.ACTION_VIEW, parsed)
        }
    }
}
