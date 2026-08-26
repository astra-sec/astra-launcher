package org.astrasec.launcher.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.astrasec.launcher.R
import org.astrasec.launcher.data.AppRepository
import org.astrasec.launcher.data.LauncherPreferences
import org.astrasec.launcher.model.GridSpanCalculator
import org.astrasec.launcher.model.LaunchableApp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

abstract class AppListFragment(layoutResId: Int) : Fragment(layoutResId) {
    protected lateinit var preferences: LauncherPreferences
    protected lateinit var repository: AppRepository

    private var executor: ExecutorService? = null
    private val loadGeneration = AtomicInteger(0)

    protected fun configureAppGrid(
        recyclerView: RecyclerView,
        minimumColumnWidthResId: Int,
        onLoaded: (List<LaunchableApp>) -> List<LaunchableApp>
    ): AppGridAdapter {
        val adapter = AppGridAdapter(
            onAppClick = ::launchApp,
            onAppLongClick = ::showAppActions
        )
        val layoutManager = GridLayoutManager(requireContext(), 1)
        val minimumColumnWidth = resources.getDimensionPixelSize(minimumColumnWidthResId)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val availableWidth = right - left
            if (availableWidth == oldRight - oldLeft) return@addOnLayoutChangeListener
            layoutManager.spanCount = GridSpanCalculator.fromWidth(
                availableWidthPx = availableWidth,
                minimumColumnWidthPx = minimumColumnWidth
            )
        }
        loadApps(adapter, onLoaded)
        return adapter
    }

    protected fun reloadApps(
        adapter: AppGridAdapter,
        onLoaded: (List<LaunchableApp>) -> List<LaunchableApp>
    ) {
        loadApps(adapter, onLoaded)
    }

    protected open fun onFavoritesChanged() = Unit

    protected fun initializeDependencies() {
        preferences = LauncherPreferences(requireContext())
        repository = AppRepository(requireContext().applicationContext)
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }

    private fun loadApps(
        adapter: AppGridAdapter,
        onLoaded: (List<LaunchableApp>) -> List<LaunchableApp>
    ) {
        val generation = loadGeneration.incrementAndGet()
        executor?.execute {
            val allApps = repository.loadLaunchableApps()
            activity?.runOnUiThread {
                if (!isAdded || generation != loadGeneration.get()) return@runOnUiThread
                adapter.submitItems(onLoaded(allApps))
            }
        }
    }

    private fun launchApp(app: LaunchableApp) {
        repository.launch(app).onFailure {
            Toast.makeText(requireContext(), R.string.app_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppActions(app: LaunchableApp) {
        val component = app.componentName.flattenToString()
        val favoriteAction = if (preferences.isFavorite(component)) {
            getString(R.string.remove_from_favorites)
        } else {
            getString(R.string.add_to_favorites)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(app.label)
            .setItems(arrayOf(favoriteAction, getString(R.string.app_info))) { dialog, which ->
                when (which) {
                    0 -> {
                        val isFavorite = preferences.toggleFavorite(component)
                        Toast.makeText(
                            requireContext(),
                            if (isFavorite) R.string.added_to_favorites else R.string.removed_from_favorites,
                            Toast.LENGTH_SHORT
                        ).show()
                        onFavoritesChanged()
                    }
                    1 -> repository.openAppInfo(app)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
