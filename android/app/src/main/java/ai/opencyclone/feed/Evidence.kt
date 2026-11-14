package ai.opencyclone.feed

/** What the owner sees before an approval. Paths are files in the app's private storage. */
data class Evidence(
    val count: Int? = null,            // number of attached items, null when unreadable
    val composeText: String? = null,   // exact text that will be sent, null when none
    val thumbs: List<String> = emptyList(), // crops of the selected items
    val framePath: String? = null,     // one picture of the hidden display with the tap position marked
)
