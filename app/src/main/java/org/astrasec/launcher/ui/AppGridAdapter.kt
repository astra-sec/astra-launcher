package org.astrasec.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.astrasec.launcher.R
import org.astrasec.launcher.model.LaunchableApp

class AppGridAdapter(
    private val onAppClick: (LaunchableApp) -> Unit,
    private val onAppLongClick: (LaunchableApp) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.AppViewHolder>() {
    private var items: List<LaunchableApp> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submitItems(newItems: List<LaunchableApp>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].stableId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val label: TextView = itemView.findViewById(R.id.appLabel)

        fun bind(app: LaunchableApp) {
            icon.setImageDrawable(app.icon)
            label.text = app.label
            itemView.contentDescription = app.label
            itemView.setOnClickListener {
                if (itemView.isInTouchMode) itemView.clearFocus()
                onAppClick(app)
            }
            itemView.setOnLongClickListener {
                if (itemView.isInTouchMode) itemView.clearFocus()
                onAppLongClick(app)
                true
            }

            label.textSize = 15f
            icon.layoutParams = icon.layoutParams.apply {
                val size = itemView.resources.getDimensionPixelSize(R.dimen.app_icon)
                width = size
                height = size
            }

            itemView.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1f
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(100L)
                    .start()
            }
        }
    }
}
