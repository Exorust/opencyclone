package ai.opencyclone.gate

import java.util.Locale

object CommitWords {
    private val words = setOf(
        "send", "post", "call", "confirm", "pay", "buy", "delete", "remove", "install",
        "submit", "publish", "transfer", "checkout", "purchase",
        "enviar", "pagar", "eliminar", "borrar", "confirmar",
        "भेजें", "भेजना", "भुगतान", "हटाएं", "हटाएँ", "मिटाएं", "पुष्टि",
        "发送", "支付", "删除", "确认"
    )
    private val camelBoundary = Regex("(?<=[\\p{Ll}\\p{M}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")
    // Combining marks belong to their letters, including Hindi vowel signs.
    private val separator = Regex("[^\\p{L}\\p{M}]+")

    fun matches(s: String?): Boolean {
        if (s == null) return false
        val tokens = camelBoundary.replace(s, " ").lowercase(Locale.ROOT).split(separator)
        return tokens.any { it in words } || tokens.zipWithNext().any { (a, b) ->
            (a == "place" && b == "order") || (a == "order" && b == "now")
        }
    }
}
