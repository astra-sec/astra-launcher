package org.astrasec.launcher.ui

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.astrasec.launcher.LauncherActivity
import org.astrasec.launcher.R

class AppDrawerFragment : AppListFragment(R.layout.fragment_app_drawer), DefaultFocusOwner {
    private lateinit var appAdapter: AppGridAdapter
    private var appGrid: RecyclerView? = null
    private var backButton: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeDependencies()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as LauncherActivity
        backButton = view.findViewById(R.id.backButton)
        backButton?.setOnClickListener { activity.showHome() }

        appGrid = view.findViewById(R.id.appGrid)
        appAdapter = configureAppGrid(
            recyclerView = appGrid!!,
            minimumColumnWidthResId = R.dimen.drawer_tile_min_width,
            onLoaded = { apps -> apps }
        )
    }

    override fun requestDefaultFocus(): Boolean {
        val firstApp = appGrid?.findViewHolderForAdapterPosition(0)?.itemView
        return firstApp?.requestDirectionalFocus() == true ||
            backButton?.requestDirectionalFocus() == true
    }

    override fun onDestroyView() {
        appGrid = null
        backButton = null
        super.onDestroyView()
    }
}
