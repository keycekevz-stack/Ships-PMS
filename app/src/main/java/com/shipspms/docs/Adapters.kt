package com.shipspms.docs

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

// ---------------- Tree ----------------

/** One visible row in the tree. node == null is the "Whole ship" row. */
data class TreeRow(
    val node: Node?,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val selected: Boolean,
    val overdue: Boolean,
    /** Text for the top row (the ship's name). */
    val label: String = "",
)

class TreeAdapter(
    private val onSelect: (Node?) -> Unit,
    private val onToggle: (Node) -> Unit,
    private val onMore: (Node, View) -> Unit,
) : ListAdapter<TreeRow, TreeAdapter.Holder>(object : DiffUtil.ItemCallback<TreeRow>() {
    override fun areItemsTheSame(a: TreeRow, b: TreeRow) = a.node?.id == b.node?.id
    override fun areContentsTheSame(a: TreeRow, b: TreeRow) = a == b && a.node?.name == b.node?.name
}) {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val indent: View = v.findViewById(R.id.indent)
        val arrow: ImageView = v.findViewById(R.id.arrow)
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val dot: View = v.findViewById(R.id.dot)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_tree, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val row = getItem(position)
        val ctx = h.itemView.context
        val dp = ctx.resources.displayMetrics.density
        h.indent.layoutParams = h.indent.layoutParams.apply { width = ((8 + row.depth * 18) * dp).toInt() }

        val node = row.node
        h.name.text = node?.name ?: row.label.ifEmpty { ctx.getString(R.string.whole_ship) }
        h.icon.setImageResource(if (node == null || row.hasChildren) R.drawable.ic_folder else R.drawable.ic_build)
        h.arrow.visibility = if (row.hasChildren) View.VISIBLE else View.INVISIBLE
        h.arrow.rotation = if (row.expanded) 90f else 0f
        h.dot.visibility = if (row.overdue) View.VISIBLE else View.GONE
        h.more.visibility = if (node == null) View.GONE else View.VISIBLE

        val highlight = com.google.android.material.color.MaterialColors.getColor(
            h.itemView, com.google.android.material.R.attr.colorPrimaryContainer
        )
        h.itemView.setBackgroundColor(if (row.selected) highlight else 0)
        h.name.paint.isFakeBoldText = row.selected

        h.itemView.setOnClickListener { onSelect(node) }
        h.arrow.setOnClickListener { if (node != null) onToggle(node) }
        h.more.setOnClickListener { if (node != null) onMore(node, it) }
        h.itemView.setOnLongClickListener { if (node != null) onMore(node, h.more); node != null }
    }
}

// ---------------- Lists (documents / jobs / work records) ----------------

data class RowItem(
    val key: String,
    val icon: Int,
    val iconTint: Int?,
    val title: String,
    val line2: String,
    val line3: String? = null,
    val badge: String? = null,
    val color: Int? = null,
    val payload: Any,
)

class RowAdapter(
    private val onClick: (Any) -> Unit,
    private val onMore: (Any, View) -> Unit,
) : ListAdapter<RowItem, RowAdapter.Holder>(object : DiffUtil.ItemCallback<RowItem>() {
    override fun areItemsTheSame(a: RowItem, b: RowItem) = a.key == b.key
    override fun areContentsTheSame(a: RowItem, b: RowItem) =
        a.title == b.title && a.line2 == b.line2 && a.line3 == b.line3 && a.badge == b.badge && a.color == b.color
}) {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val strip: View = v.findViewById(R.id.strip)
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val badge: TextView = v.findViewById(R.id.badge)
        val line2: TextView = v.findViewById(R.id.line2)
        val line3: TextView = v.findViewById(R.id.line3)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = getItem(position)
        h.icon.setImageResource(item.icon)
        h.icon.imageTintList = item.iconTint?.let { ColorStateList.valueOf(it) }
        h.title.text = item.title
        h.line2.text = item.line2
        h.line3.text = item.line3
        h.line3.visibility = if (item.line3.isNullOrBlank()) View.GONE else View.VISIBLE

        if (item.color != null) {
            h.strip.visibility = View.VISIBLE
            h.strip.setBackgroundColor(item.color)
        } else {
            h.strip.visibility = View.INVISIBLE
        }
        if (item.badge != null && item.color != null) {
            h.badge.visibility = View.VISIBLE
            h.badge.text = item.badge
            h.badge.backgroundTintList = ColorStateList.valueOf(item.color)
        } else {
            h.badge.visibility = View.GONE
        }

        h.itemView.setOnClickListener { onClick(item.payload) }
        h.itemView.setOnLongClickListener { onMore(item.payload, h.more); true }
        h.more.setOnClickListener { onMore(item.payload, it) }
    }
}
