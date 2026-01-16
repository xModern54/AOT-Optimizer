package com.xmodern.aotoptimizer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = getApplication<Application>().applicationContext
    private val prefs = context.getSharedPreferences("aot_optimizer_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var batchJob: Job? = null
    private var sizeCalcJob: Job? = null
    private var refreshJob: Job? = null

    data class UiState(
        val apps: List<AppItem> = emptyList(),
        val isLoading: Boolean = true,
        val showBatchSheet: Boolean = false,
        val newAppsDetected: List<AppItem> = emptyList(),
        val showSystemApps: Boolean = false,
        val isBatchRunning: Boolean = false,
        val batchProgress: Float = 0f,
        val batchCurrentApp: String = "",
        val batchLogs: List<String> = emptyList(),
        val batchTotal: Int = 0,
        val batchCurrentIndex: Int = 0,
        val globalSizeKb: Long = 0L,
        val isCalculatingGlobalSize: Boolean = false
    )

    fun calculateGlobalSize() {
        if (_uiState.value.isCalculatingGlobalSize) return
        sizeCalcJob?.cancel()
        sizeCalcJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isCalculatingGlobalSize = true, globalSizeKb = 0L)
            val allPackages = ShellHelper.getAllPackages()
            var currentTotal = 0L
            allPackages.forEach { pkg ->
                currentTotal += ShellHelper.getArtifactsSizeInKb(pkg)
                _uiState.value = _uiState.value.copy(globalSizeKb = currentTotal)
            }
            _uiState.value = _uiState.value.copy(isCalculatingGlobalSize = false)
        }
    }

    fun startBatchOptimize(mode: String) {
        batchJob?.cancel()
        batchJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isBatchRunning = true, batchProgress = 0f, batchLogs = listOf("> Starting system optimization..."))
            val allPackages = ShellHelper.getAllPackages()
            val total = allPackages.size
            _uiState.value = _uiState.value.copy(batchTotal = total)

            allPackages.forEachIndexed { index, pkg ->
                _uiState.value = _uiState.value.copy(batchCurrentApp = pkg, batchCurrentIndex = index + 1, batchProgress = (index + 1).toFloat() / total)
                val sizeBefore = ShellHelper.getArtifactsSizeInKb(pkg)
                val currentLogs = _uiState.value.batchLogs
                _uiState.value = _uiState.value.copy(batchLogs = if (currentLogs.size > 50) currentLogs.drop(1) + "> Compiling: $pkg" else currentLogs + "> Compiling: $pkg")
                
                if (mode == "reset") ShellHelper.resetPackage(pkg) else ShellHelper.compilePackage(pkg, mode)
                
                val sizeAfter = ShellHelper.getArtifactsSizeInKb(pkg)
                _uiState.value = _uiState.value.copy(globalSizeKb = _uiState.value.globalSizeKb + (sizeAfter - sizeBefore))
            }
            _uiState.value = _uiState.value.copy(isBatchRunning = false, batchProgress = 1f, batchCurrentApp = "DONE")
        }
    }

    fun stopBatchOptimize() {
        batchJob?.cancel()
        _uiState.value = _uiState.value.copy(isBatchRunning = false)
    }

    fun toggleSystemApps() {
        _uiState.value = _uiState.value.copy(showSystemApps = !_uiState.value.showSystemApps)
        refreshData()
    }

    fun refreshData() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val showSystem = _uiState.value.showSystemApps
            
            // FAST SCAN: Get launchable apps instantly using intent query
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val launchablePackages = pm.queryIntentActivities(launcherIntent, 0)
                .map { it.activityInfo.packageName }.toSet()

            val allInstalled = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            val filteredApps = allInstalled.filter { appInfo ->
                val isLaunchable = launchablePackages.contains(appInfo.packageName)
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (showSystem) true else (isLaunchable || isUpdatedSystem)
            }.map { appInfo ->
                AppItem(
                    label = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm)
                )
            }

            // PERSISTENCE & SORTING
            val knownPackages = prefs.getStringSet("known_packages", emptySet()) ?: emptySet()
            val newlyDetected = filteredApps.filter { !knownPackages.contains(it.packageName) && knownPackages.isNotEmpty() }
            
            val oldList = _uiState.value.apps
            val initialList = filteredApps.map { app ->
                val existing = oldList.find { it.packageName == app.packageName }
                app.copy(
                    isNew = newlyDetected.any { it.packageName == app.packageName } || (existing?.isNew == true),
                    status = existing?.status ?: "loading...",
                    size = existing?.size ?: "...",
                    framework = existing?.framework ?: "Native"
                )
            }.sortedWith(compareByDescending<AppItem> { it.isNew }.thenBy { it.label })

            // SHOW LIST IMMEDIATELY
            _uiState.value = _uiState.value.copy(
                apps = initialList,
                isLoading = false,
                showBatchSheet = newlyDetected.isNotEmpty(),
                newAppsDetected = newlyDetected
            )

            if (filteredApps.isNotEmpty()) {
                prefs.edit().putStringSet("known_packages", filteredApps.map { it.packageName }.toSet()).apply()
            }

            // PROGRESSIVE UPDATE: Fetch heavy metrics one by one (Sequential to avoid shell lag)
            initialList.forEach { app ->
                val status = ShellHelper.getCompilationFilter(app.packageName)
                val size = ShellHelper.getArtifactsSize(app.packageName)
                val framework = ShellHelper.getAppFramework(app.packageName)
                updateAppDetails(app.packageName, status, size, framework)
            }
        }
    }

    fun updateAppDetails(packageName: String, status: String, size: String, framework: String) {
        val currentList = _uiState.value.apps
        val updatedList = currentList.map { 
            if (it.packageName == packageName) it.copy(status = status, size = size, framework = framework) else it 
        }
        _uiState.value = _uiState.value.copy(apps = updatedList)
    }

    fun dismissBatchSheet() {
        _uiState.value = _uiState.value.copy(showBatchSheet = false)
    }
}