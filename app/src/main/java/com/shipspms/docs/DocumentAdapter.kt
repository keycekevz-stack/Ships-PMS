package com.shipspms.docs

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DocumentAdapter(
    private val onOpen: (ShipDocument) -> Unit,
    private val onToggleFavorite: (ShipDocument) -> Unit,
    private val onMore: (ShipDocument, View) -> Unit,
) : ListAdapter<ShipDocument, DocumentAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val star: ImageButton = view.findViewById(R.id.star)
        val more: ImageButton = view.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val doc = getItem(position)
        val ctx = holder.itemView.context
        holder.title.text = doc.name
        val size = Formatter.formatShortFileSize(ctx, doc.file.length())
        val added = DateUtils.formatDateTime(ctx, doc.file.lastModified(), DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_DATE)
        holder.subtitle.text = "${doc.category} · $size · $added"
        holder.star.setImageResource(if (doc.favorite) R.drawable.ic_star else R.drawable.ic_star_border)
        if (doc.favorite) holder.star.setColorFilter(ctx.getColor(R.color.amber)) else holder.star.clearColorFilter()
        holder.itemView.setOnClickListener { onOpen(doc) }
        holder.itemView.setOnLongClickListener { onMore(doc, holder.more); true }
        holder.star.setOnClickListener { onToggleFavorite(doc) }
        holder.more.setOnClickListener { onMore(doc, it) }
    }

    private object Diff : DiffUtil.ItemCallback<ShipDocument>() {
        override fun areItemsTheSame(a: ShipDocument, b: ShipDocument) = a.file.absolutePath == b.file.absolutePath
        override fun areContentsTheSame(a: ShipDocument, b: ShipDocument) =
            a == b && a.file.length() == b.file.length()
    }
}
