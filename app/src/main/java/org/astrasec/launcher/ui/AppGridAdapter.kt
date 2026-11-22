package org.astrasec.launcher.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.astrasec.launcher.R
import org.astrasec.launcher.model.LaunchableApp
import org.astrasec.launcher.model.FavoriteOrder

class AppGridAdapter(
    private val onAppClick: (LaunchableApp) -> Unit,
    private val onAppLongClick: (LaunchableApp) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.AppViewHolder>() {
    private var items: List<LaunchableApp> = emptyList()
    private var arranging = false
    private var pickedComponent: String? = null
    var onArrangeTouchDown: ((AppViewHolder) -> Unit)? = null

    fun appAt(position: Int): LaunchableApp? = items.getOrNull(position)

    fun indexOf(component: String): Int = items.indexOfFirst {
        it.componentName.flattenToString() == component
    }

    fun orderedComponents(): List<String> = items.map { it.componentName.flattenToString() }

    fun moveItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        items = FavoriteOrder.move(items, from, to)
        notifyItemMoved(from, to)
    }

    fun setArrangement(active: Boolean, picked: String?) {
        arranging = active
        pickedComponent = picked
        notifyItemRangeChanged(0, itemCount, ARRANGEMENT_PAYLOAD)
    }

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

    override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.bindArrangement(items[position])
        else holder.bind(items[position])
    }

    override fun onViewAttachedToWindow(holder: AppViewHolder) {
        super.onViewAttachedToWindow(holder)
        appAt(holder.adapterPosition)?.let(holder::bindArrangement)
    }

    override fun onViewDetachedFromWindow(holder: AppViewHolder) {
        holder.stopWiggle()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        holder.stopWiggle()
        holder.itemView.isActivated = false
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val label: TextView = itemView.findViewById(R.id.appLabel)
        private var wiggle: ObjectAnimator? = null

        // The touch listener observes DOWN only and returns false: View handles clicks and accessibility.
        @SuppressLint("ClickableViewAccessibility")
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
            itemView.setOnTouchListener { _, event ->
                if (arranging && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onArrangeTouchDown?.invoke(this)
                }
                false
            }
            bindArrangement(app)

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

        fun bindArrangement(app: LaunchableApp) {
            itemView.isActivated = arranging && app.componentName.flattenToString() == pickedComponent
            if (arranging && wiggle == null) {
                // Animate the icon only, leaving focus scaling and drag translation independent.
                val tilt = if (app.stableId % 2L == 0L) 1.6f else -1.6f
                wiggle = ObjectAnimator.ofFloat(icon, View.ROTATION, -tilt, tilt).apply {
                    duration = 160L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else if (!arranging) {
                stopWiggle()
            }
        }

        fun stopWiggle() {
            wiggle?.cancel()
            wiggle = null
            icon.rotation = 0f
        }
    }

    private companion object {
        const val ARRANGEMENT_PAYLOAD = "arrangement"
    }
}
