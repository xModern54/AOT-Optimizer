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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = getApplication<Application>().applicationContext
    private val prefs = context.getSharedPreferences("aot_optimizer_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // --- SEARCH EXTENSION ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive = _isSearchActive.asStateFlow()

    // Use this derived flow in the UI instead of uiState.apps
    val filteredApps: StateFlow<List<AppItem>> = combine(_uiState, _searchQuery) { state, query ->
        if (query.isBlank()) {
            state.apps
        } else {
            state.apps.filter {
                it.label.contains(query, ignoreCase = true) || 
                it.packageName.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) _searchQuery.value = ""
    }
    // ------------------------

    private var batchJob: Job? = null
    private var sizeCalcJob: Job? = null
    private var refreshJob: Job? = null
    private var needsFullRefresh = false

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
        val isCalculatingGlobalSize: Boolean = false,
        val sortOption: SortOption = SortOption.NAME,
        val showAllApps: Boolean = false,
        val isDexOptDisabled: Boolean = false // New State
    )

    init {
        val savedSort = prefs.getString("sort_option", SortOption.NAME.name)
        val option = try { SortOption.valueOf(savedSort!!) } catch (e: Exception) { SortOption.NAME }
        _uiState.value = _uiState.value.copy(sortOption = option)
        checkDexOptStatus() // Check on init
    }

    fun checkDexOptStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val disabled = ShellHelper.isSystemDexOptDisabled()
            _uiState.value = _uiState.value.copy(isDexOptDisabled = disabled)
        }
    }

    fun toggleDexOpt() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _uiState.value.isDexOptDisabled
            if (current) {
                // Was disabled -> Enable it
                ShellHelper.enableSystemDexOpt()
            } else {
                // Was enabled -> Disable it
                ShellHelper.disableSystemDexOpt()
            }
            checkDexOptStatus() // Refresh state
        }
    }

    fun setSortOption(option: SortOption) {
        prefs.edit().putString("sort_option", option.name).apply()
        _uiState.value = _uiState.value.copy(sortOption = option)
        applySorting()
    }

    fun toggleShowAllApps() {
        refreshJob?.cancel() // KILL PREVIOUS JOB
        _uiState.value = _uiState.value.copy(showAllApps = !_uiState.value.showAllApps)
        needsFullRefresh = true
        refreshData()
    }

    private fun applySorting() {
        val currentApps = _uiState.value.apps
        val option = _uiState.value.sortOption
        val sorted = currentApps.sortedWith(
            compareByDescending<AppItem> { it.isNew }
                .then(
                    when (option) {
                        SortOption.NAME -> compareBy { it.label }
                        SortOption.STATUS -> compareByDescending { it.statusPriority }
                        SortOption.SIZE -> compareByDescending { it.sizeBytes }
                    }
                )
        )
        _uiState.value = _uiState.value.copy(apps = sorted)
    }

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
            _uiState.value = _uiState.value.copy(
                isBatchRunning = true, 
                batchProgress = 0f, 
                batchLogs = listOf("> Starting global optimization...", "> Mode: $mode"),
                batchCurrentIndex = 0
            )
            val allPackages = ShellHelper.getAllPackages()
            val total = allPackages.size
            _uiState.value = _uiState.value.copy(batchTotal = total)

            allPackages.forEachIndexed { index, pkg ->
                if (!isActive) return@forEachIndexed // Handle cancellation
                
                // Update Progress & Logs
                val currentLogs = _uiState.value.batchLogs.takeLast(50) // Keep log size manageable
                _uiState.value = _uiState.value.copy(
                    batchCurrentApp = pkg, 
                    batchCurrentIndex = index + 1, 
                    batchProgress = (index + 1).toFloat() / total,
                    batchLogs = currentLogs + "> Compiling: $pkg"
                )
                
                val sizeBefore = ShellHelper.getArtifactsSizeInKb(pkg)
                
                if (mode == "reset") ShellHelper.resetPackage(pkg) else ShellHelper.compilePackage(pkg, mode)
                
                val sizeAfter = ShellHelper.getArtifactsSizeInKb(pkg)
                _uiState.value = _uiState.value.copy(globalSizeKb = _uiState.value.globalSizeKb + (sizeAfter - sizeBefore))
            }
            needsFullRefresh = true
            _uiState.value = _uiState.value.copy(isBatchRunning = false, batchProgress = 1f, batchCurrentApp = "DONE", batchLogs = _uiState.value.batchLogs + "> COMPLETED.")
        }
    }

    fun stopBatchOptimize() {
        batchJob?.cancel()
        resetBatchState()
    }
    
    fun resetBatchState() {
        _uiState.value = _uiState.value.copy(
            isBatchRunning = false,
            batchProgress = 0f,
            batchCurrentApp = "",
            batchLogs = emptyList(),
            batchCurrentIndex = 0,
            batchTotal = 0
        )
    }

    fun toggleSystemApps() {
        _uiState.value = _uiState.value.copy(showSystemApps = !_uiState.value.showSystemApps)
        needsFullRefresh = true
        refreshData()
    }

    fun refreshData() {
        // No blocking isActive check here, handled by toggleShowAllApps cancel()
        
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val showSystem = _uiState.value.showSystemApps
            val showAll = _uiState.value.showAllApps
            
            // --- BRANCHING LOGIC ---
            val currentSystemState = if (showAll) {
                // GOD MODE: Fetch everything via Shell
                val rawPackages = ShellHelper.getAllSystemPackages()
                // Batch fetch PM info to avoid lag
                val pmList = try {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA).associateBy { it.packageName }
                } catch (e: Exception) { emptyMap() }

                rawPackages.map { pkgName ->
                    pmList[pkgName] ?: ApplicationInfo().apply { 
                        packageName = pkgName 
                        // Minimal info for hidden packages
                    }
                }
            } else {
                // EXISTING USER MODE (UNTOUCHED)
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val launchablePackages = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()
                val allInstalled = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                
                allInstalled.filter { info ->
                    val isLaunchable = launchablePackages.contains(info.packageName)
                    val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (showSystem) true else (isLaunchable || isUpdatedSystem)
                }
            }

            // 2. Load persistence (The real memory)
            val knownPackages = prefs.getStringSet("known_packages", emptySet()) ?: emptySet()
            val isFirstRunEver = knownPackages.isEmpty()

            val oldList = _uiState.value.apps
            val force = needsFullRefresh
            needsFullRefresh = false

            // 3. Smart Merge with persistent "isNew" check
            val mergedList = currentSystemState.map { info ->
                val existing = oldList.find { it.packageName == info.packageName }
                val isReallyNew = !knownPackages.contains(info.packageName) && !isFirstRunEver && !showAll // DISABLED NEW TAG IN GOD MODE
                
                if (existing != null && !force) {
                    existing.copy(isNew = if (showAll) false else (isReallyNew || existing.isNew))
                } else {
                    // Safe icon loading
                    val icon = try { info.loadIcon(pm) } catch(e: Exception) { context.getDrawable(android.R.drawable.sym_def_app_icon)!! }
                    val label = try { info.loadLabel(pm).toString() } catch(e: Exception) { info.packageName }
                    
                    AppItem(
                        label = label,
                        packageName = info.packageName,
                        icon = icon,
                        status = "loading...",
                        isNew = isReallyNew
                    )
                }
            }

            // 4. Update UI
            _uiState.value = _uiState.value.copy(apps = mergedList, isLoading = false)
            applySorting()

            // 5. Trigger Batch Sheet for new apps (ONLY IN USER MODE)
            val newlyDetected = mergedList.filter { it.isNew }
            if (newlyDetected.isNotEmpty() && !showAll) {
                _uiState.value = _uiState.value.copy(showBatchSheet = true, newAppsDetected = newlyDetected)
            } else {
                _uiState.value = _uiState.value.copy(showBatchSheet = false)
            }
            
            // 6. Sync persistence
            if (currentSystemState.isNotEmpty()) {
                prefs.edit().putStringSet("known_packages", currentSystemState.map { it.packageName }.toSet()).apply()
            }

            // 7. Investigate
            val scanQueue = mergedList.filter { it.status == "loading..." }.sortedBy { it.label }
            scanQueue.forEach { app ->
                val status = ShellHelper.getCompilationFilter(app.packageName)
                val sizeStr = ShellHelper.getArtifactsSize(app.packageName)
                val sizeKb = ShellHelper.getArtifactsSizeInKb(app.packageName)
                val framework = ShellHelper.getAppFramework(app.packageName)
                updateAppDetails(app.packageName, status, sizeStr, sizeKb, framework)
            }
        }
    }

    fun updateAppDetails(packageName: String, status: String, size: String, sizeBytes: Long, framework: String) {
        val currentList = _uiState.value.apps
        val updatedList = currentList.map { 
            if (it.packageName == packageName) it.copy(status = status, size = size, sizeBytes = sizeBytes, framework = framework) else it 
        }
        _uiState.value = _uiState.value.copy(apps = updatedList)
        if (_uiState.value.sortOption != SortOption.NAME) applySorting()
    }

    fun dismissBatchSheet() {
        _uiState.value = _uiState.value.copy(showBatchSheet = false)
    }
}