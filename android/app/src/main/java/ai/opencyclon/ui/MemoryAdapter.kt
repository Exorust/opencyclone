package ai.opencyclon.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ai.opencyclon.R
import ai.opencyclon.db.GlobalMemoryEntity

class MemoryAdapter(
    private val onClick: (GlobalMemoryEntity) -> Unit,
    private val onLongClick: (GlobalMemoryEntity) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

    private val items = mutableListOf<GlobalMemoryEntity>()

    fun submitList(memories: List<GlobalMemoryEntity>) {
        items.clear()
        items.addAll(memories)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    companion object {
        /** "Off · Added by you · Food · Nov 12, 14:03"; the first part only when the memory is off. */
        fun metaLine(off: String?, source: String, category: String, updated: String): String =
            listOfNotNull(off, source, category, updated).joinToString(" · ")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false)
        return ViewHolder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (GlobalMemoryEntity) -> Unit,
        private val onLongClick: (GlobalMemoryEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val keyText: TextView = itemView.findViewById(R.id.text_key)
        private val valueText: TextView = itemView.findViewById(R.id.text_value)
        private val metaText: TextView = itemView.findViewById(R.id.text_meta)

        fun bind(memory: GlobalMemoryEntity) {
            val ctx = itemView.context
            keyText.text = memory.key
            valueText.text = memory.value
            val source = if (memory.source == GlobalMemoryEntity.SOURCE_AI) ctx.getString(R.string.memory_source_ai) else ctx.getString(R.string.memory_source_user)
            val category = if (memory.category.isBlank()) ctx.getString(R.string.memory_uncategorized) else memory.category
            val updated = DateFormat.format("MMM d, HH:mm", memory.updatedAt).toString()
            val off = if (memory.enabled) null else ctx.getString(R.string.memory_status_disabled)
            metaText.text = metaLine(off, source, category, updated)
            itemView.setOnClickListener { onClick(memory) }
            itemView.setOnLongClickListener {
                onLongClick(memory)
                true
            }
        }
    }
}
