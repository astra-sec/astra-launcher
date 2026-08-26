package org.astrasec.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import org.astrasec.launcher.LauncherActivity
import org.astrasec.launcher.R
import org.astrasec.launcher.device.DeviceControlRepository
import org.astrasec.launcher.device.SystemSettingKind
import org.astrasec.launcher.dailyimage.DailyImageRepository
import org.astrasec.launcher.dailyimage.DailyImageSnapshot
import org.astrasec.launcher.fileaccess.FileAccessRepository
import org.astrasec.launcher.model.LaunchableApp
import org.astrasec.launcher.weather.WeatherRepository
import org.astrasec.launcher.weather.WeatherSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HomeFragment : AppListFragment(0), DefaultFocusOwner {
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !fileAccess.openSelectedDocument(uri)) {
            Toast.makeText(context, R.string.file_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var favoriteAdapter: AppGridAdapter
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var dailyImageRepository: DailyImageRepository
    private lateinit var deviceControls: DeviceControlRepository
    private lateinit var fileAccess: FileAccessRepository
    private var weatherExecutor: ExecutorService? = null
    private var dailyImageExecutor: ExecutorService? = null
    private var favoriteGrid: RecyclerView? = null
    private var emptyFavorites: TextView? = null
    private var allAppsButton: View? = null
    private var weatherStatus: TextView? = null
    private var weatherAttributionUrl: String? = null
    private var dailyImageWallpaper: ImageView? = null
    private var wallpaperScrim: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeDependencies()
        weatherRepository = WeatherRepository(requireContext().applicationContext)
        dailyImageRepository = DailyImageRepository(requireContext().applicationContext)
        deviceControls = DeviceControlRepository(requireContext().applicationContext)
        fileAccess = FileAccessRepository(requireContext().applicationContext)
        weatherExecutor = Executors.newSingleThreadExecutor()
        dailyImageExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as LauncherActivity
        dailyImageWallpaper = view.findViewById(R.id.dailyImageWallpaper)
        wallpaperScrim = view.findViewById(R.id.wallpaperScrim)
        refreshDailyImage()
        allAppsButton = view.findViewById(R.id.allAppsButton)
        allAppsButton?.setOnClickListener { activity.showAllApps() }
        bindSystemSetting(view, R.id.wifiButton, SystemSettingKind.WIFI)
        bindSystemSetting(view, R.id.bluetoothButton, SystemSettingKind.BLUETOOTH)
        view.findViewById<View>(R.id.inputSourcesButton)
            .setOnClickListener { activity.showInputSources() }
        bindFileAccess(view)
        view.findViewById<View>(R.id.settingsButton).setOnClickListener { activity.showSettings() }
        emptyFavorites = view.findViewById(R.id.emptyFavorites)
        weatherStatus = view.findViewById<TextView>(R.id.weatherStatus).apply {
            setOnClickListener { openWeatherAttribution() }
            setOnFocusChangeListener { status, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1f
                status.animate().scaleX(scale).scaleY(scale).setDuration(100L).start()
            }
        }
        weatherRepository.cachedWeather()?.let(::showWeather)
        refreshWeather()

        favoriteGrid = view.findViewById(R.id.favoriteGrid)
        favoriteAdapter = configureAppGrid(
            recyclerView = favoriteGrid!!,
            minimumColumnWidthResId = R.dimen.favorite_tile_min_width,
            onLoaded = ::favoriteApps
        )
    }

    override fun requestDefaultFocus(): Boolean {
        val firstApp = favoriteGrid?.findViewHolderForAdapterPosition(0)?.itemView
        val appFocused = firstApp?.requestDirectionalFocus() == true
        val fallbackFocused = !appFocused && allAppsButton?.requestDirectionalFocus() == true
        return appFocused || fallbackFocused
    }

    override fun onFavoritesChanged() {
        reloadApps(favoriteAdapter, ::favoriteApps)
    }

    private fun refreshWeather() {
        weatherExecutor?.execute {
            val weather = weatherRepository.refreshWeather() ?: return@execute
            activity?.runOnUiThread {
                if (isAdded && view != null) showWeather(weather)
            }
        }
    }

    private fun refreshDailyImage() {
        dailyImageExecutor?.execute {
            val cached = dailyImageRepository.cachedImage()
            cached?.let(::decodeAndShowDailyImage)
            val refreshed = dailyImageRepository.refreshDailyImage() ?: return@execute
            if (refreshed.metadata.updatedAtMillis != cached?.metadata?.updatedAtMillis) {
                decodeAndShowDailyImage(refreshed)
            }
        }
    }

    private fun decodeAndShowDailyImage(snapshot: DailyImageSnapshot) {
        val metrics = resources.displayMetrics
        val bitmap = dailyImageRepository.decodeForScreen(
            snapshot,
            metrics.widthPixels,
            metrics.heightPixels
        ) ?: return
        activity?.runOnUiThread {
            if (!isAdded || view == null) return@runOnUiThread
            wallpaperScrim?.visibility = View.VISIBLE
            dailyImageWallpaper?.apply {
                animate().cancel()
                alpha = 0f
                visibility = View.VISIBLE
                setImageBitmap(bitmap)
                animate().alpha(1f).setDuration(350L).start()
            }
        }
    }

    private fun showWeather(weather: WeatherSnapshot) {
        weatherAttributionUrl = weather.provider.attributionUrl
        weatherStatus?.apply {
            text = weather.statusText
            contentDescription = getString(
                R.string.weather_status_with_source_description,
                weather.statusText,
                weather.provider.displayName
            )
        }
    }

    private fun openWeatherAttribution() {
        val url = weatherAttributionUrl ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun bindSystemSetting(root: View, viewId: Int, kind: SystemSettingKind) {
        val button = root.findViewById<View>(viewId)
        val target = deviceControls.setting(kind)
        button.visibility = if (target == null) View.GONE else View.VISIBLE
        button.setOnClickListener {
            if (target != null && !deviceControls.openSetting(target)) {
                Toast.makeText(context, R.string.settings_launch_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindFileAccess(root: View) {
        root.findViewById<View>(R.id.filesButton).apply {
            visibility = if (fileAccess.isAvailable()) View.VISIBLE else View.GONE
            setOnClickListener {
                if (fileAccess.openFileManager()) return@setOnClickListener
                if (fileAccess.canPickDocument()) {
                    openDocumentLauncher.launch(arrayOf(FileAccessRepository.ANY_MIME_TYPE))
                } else {
                    Toast.makeText(context, R.string.file_manager_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun favoriteApps(allApps: List<LaunchableApp>): List<LaunchableApp> {
        preferences.ensureInitialFavorites(
            allApps.map { app -> app.componentName.flattenToString() }
        )
        val appByComponent = allApps.associateBy { app -> app.componentName.flattenToString() }
        val favorites = preferences.favoriteComponents().mapNotNull(appByComponent::get)
        emptyFavorites?.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
        return favorites
    }

    override fun onDestroyView() {
        favoriteGrid = null
        emptyFavorites = null
        allAppsButton = null
        weatherStatus = null
        weatherAttributionUrl = null
        dailyImageWallpaper?.apply {
            animate().cancel()
            setImageDrawable(null)
        }
        dailyImageWallpaper = null
        wallpaperScrim = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        weatherExecutor?.shutdownNow()
        weatherExecutor = null
        dailyImageExecutor?.shutdownNow()
        dailyImageExecutor = null
        super.onDestroy()
    }
}
