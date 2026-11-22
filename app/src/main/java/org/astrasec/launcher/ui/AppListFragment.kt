package org.astrasec.launcher.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.astrasec.launcher.R
import org.astrasec.launcher.LauncherActivity
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
        onLoaded: (List<LaunchableApp>) -> List<LaunchableApp>,
        onAppClick: (LaunchableApp) -> Unit = ::launchApp,
        onAppLongClick: (LaunchableApp) -> Unit = ::showAppActions
    ): AppGridAdapter {
        val adapter = AppGridAdapter(
            onAppClick = onAppClick,
            onAppLongClick = onAppLongClick
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
    protected open fun onAppsLoaded() = Unit

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
                if (!isAdded || view == null || generation != loadGeneration.get()) return@runOnUiThread
                adapter.submitItems(onLoaded(allApps))
                onAppsLoaded()
            }
        }
    }

    protected fun launchApp(app: LaunchableApp) {
        repository.launch(app).onFailure {
            Toast.makeText(requireContext(), R.string.app_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    protected fun showAppActions(app: LaunchableApp) {
        val component = app.componentName.flattenToString()
        val favoriteAction = if (preferences.isFavorite(component)) {
            getString(R.string.remove_from_favorites)
        } else {
            getString(R.string.add_to_favorites)
        }

        val actions = mutableListOf(favoriteAction, getString(R.string.app_info))
        if (preferences.isFavorite(component)) actions.add(getString(R.string.arrange_favorites))
        AlertDialog.Builder(requireContext())
            .setTitle(app.label)
            .setItems(actions.toTypedArray()) { dialog, which ->
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
                    2 -> (requireActivity() as LauncherActivity).showFavoriteOrder(component)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
