package org.astrasec.launcher.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.astrasec.launcher.R
import org.astrasec.launcher.data.LauncherPreferences
import org.astrasec.launcher.model.FavoriteOrder

/** A transient editing state of the home grid, shared by touch and D-pad input. */
class FavoriteReorderController(
    private val grid: RecyclerView,
    private val adapter: AppGridAdapter,
    private val preferences: LauncherPreferences,
    private val onStateChanged: (Boolean) -> Unit
) {
    var isArranging = false
        private set
    private var pickedComponent: String? = null
    private var confirmKeyDown = false
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())
    private val idleExit = Runnable {
        if (dragging) onUserInput() else finish()
    }
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        // Long press opens the app menu; touch dragging starts explicitly only in arrangement mode.
        override fun isLongPressDragEnabled(): Boolean = false

        override fun getMovementFlags(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int =
            if (isArranging) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) else 0

        override fun onMove(
            recyclerView: RecyclerView,
            holder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            if (!isArranging || holder.adapterPosition == RecyclerView.NO_POSITION ||
                target.adapterPosition == RecyclerView.NO_POSITION) return false
            move(holder.adapterPosition, target.adapterPosition, focus = false)
            return true
        }

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun chooseDropTarget(
            selected: RecyclerView.ViewHolder,
            dropTargets: MutableList<RecyclerView.ViewHolder>,
            curX: Int,
            curY: Int
        ): RecyclerView.ViewHolder? {
            // A grid drop follows the nearest cell center, including when moving left/up.
            // The default edge-crossing rule can require dragging past the intended cell.
            val centerX = curX + selected.itemView.width / 2f
            val centerY = curY + selected.itemView.height / 2f
            fun distance(holder: RecyclerView.ViewHolder): Float {
                val dx = holder.itemView.left + holder.itemView.width / 2f - centerX
                val dy = holder.itemView.top + holder.itemView.height / 2f - centerY
                return dx * dx + dy * dy
            }
            return dropTargets.minByOrNull(::distance)?.takeIf { distance(it) < distance(selected) }
        }

        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                adapter.appAt(holder.adapterPosition)?.let { begin(it.componentName.flattenToString()) }
                dragging = true
            } else {
                dragging = false
                onUserInput()
            }
            super.onSelectedChanged(holder, actionState)
        }
    })

    init {
        touchHelper.attachToRecyclerView(grid)
        adapter.onArrangeTouchDown = { holder ->
            adapter.appAt(holder.adapterPosition)?.takeIf { isArranging }?.let {
                begin(it.componentName.flattenToString())
                touchHelper.startDrag(holder)
            }
        }
    }

    fun begin(component: String) {
        val position = adapter.indexOf(component)
        if (position < 0) return
        if (!isArranging) {
            isArranging = true
            onStateChanged(true)
            Toast.makeText(grid.context, R.string.order_instructions, Toast.LENGTH_LONG).show()
        }
        pickedComponent = component
        adapter.setArrangement(true, component)
        focusPosition(position)
        onUserInput()
    }

    fun onUserInput() {
        handler.removeCallbacks(idleExit)
        if (isArranging) handler.postDelayed(idleExit, IDLE_TIMEOUT_MS)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!isArranging) return false
        onUserInput()
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> FavoriteOrder.Direction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> FavoriteOrder.Direction.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> FavoriteOrder.Direction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> FavoriteOrder.Direction.DOWN
            else -> null
        }
        val picked = pickedComponent
        if (direction != null && picked != null) {
            if (event.action == KeyEvent.ACTION_DOWN && !dragging) {
                val from = adapter.indexOf(picked)
                val columns = (grid.layoutManager as GridLayoutManager).spanCount
                move(from, FavoriteOrder.target(from, adapter.itemCount, columns, direction), focus = true)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            // Consume the complete press so finishing cannot also launch the focused app.
            // An UP from selecting the menu entry must not immediately end arrangement.
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) confirmKeyDown = true
                KeyEvent.ACTION_UP -> {
                    if (confirmKeyDown && !event.isCanceled) finish()
                    confirmKeyDown = false
                }
            }
            return true
        }
        return false
    }

    fun finish(): Boolean {
        if (!isArranging) return false
        isArranging = false
        pickedComponent = null
        confirmKeyDown = false
        handler.removeCallbacks(idleExit)
        // Also cancel an in-progress touch drag if Back is pressed while the finger is down.
        touchHelper.attachToRecyclerView(null)
        touchHelper.attachToRecyclerView(grid)
        dragging = false
        adapter.setArrangement(false, null)
        onStateChanged(false)
        return true
    }

    fun dispose() {
        finish()
        handler.removeCallbacksAndMessages(null)
        adapter.onArrangeTouchDown = null
        touchHelper.attachToRecyclerView(null)
    }

    private fun move(from: Int, to: Int, focus: Boolean) {
        if (from == to || from !in 0 until adapter.itemCount || to !in 0 until adapter.itemCount) return
        adapter.moveItem(from, to)
        preferences.reorderFavorites(adapter.orderedComponents())
        if (focus) focusPosition(to)
        onUserInput()
    }

    private fun focusPosition(position: Int) {
        grid.scrollToPosition(position)
        grid.post {
            if (isArranging) grid.findViewHolderForAdapterPosition(position)?.itemView?.requestDirectionalFocus()
        }
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 30_000L
    }
}
