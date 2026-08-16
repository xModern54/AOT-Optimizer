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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = getApplication<Application>().applicationContext
    private val prefs = context.getSharedPreferences("aot_optimizer_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KNOWN_PACKAGES_KEY = "known_packages"
        private const val PENDING_NEW_PACKAGES_KEY = "pending_new_packages"
        private const val NEW_APPS_TRACKER_READY_KEY = "new_apps_tracker_ready"
    }
    
    // Repository for Contracts
    private val repository = OptimizationRepository(context)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Toast Events
    private val _toastMessage = Channel<String>(Channel.BUFFERED)
    val toastMessage = _toastMessage.receiveAsFlow()

    // Search Logic
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive = _isSearchActive.asStateFlow()

    // Filtered List for UI
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
        val isDexOptDisabled: Boolean = false,
        val updatesDetected: List<OptimizationContract> = emptyList(),
        val showUpdatesSheet: Boolean = false,
        val checkUpdatesProgress: Float = 0f,
        val savedProfiles: List<OptimizationContract> = emptyList(),
        val showProfilesSheet: Boolean = false
    )

    init {
        val savedSort = prefs.getString("sort_option", SortOption.NAME.name)
        val option = try { SortOption.valueOf(savedSort!!) } catch (e: Exception) { SortOption.NAME }
        _uiState.value = _uiState.value.copy(sortOption = option)
        checkDexOptStatus()
    }

    // --- Search Functions ---
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) _searchQuery.value = ""
    }

    // --- DexOpt Functions ---
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
                ShellHelper.enableSystemDexOpt()
            }
            else {
                ShellHelper.disableSystemDexOpt()
            }
            checkDexOptStatus()
        }
    }

    // --- Profile Management ---
    fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val profiles = repository.getAllContracts()
            _uiState.value = _uiState.value.copy(savedProfiles = profiles, showProfilesSheet = true)
        }
    }

    fun deleteProfile(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeContract(packageName)
            val profiles = repository.getAllContracts()
            _uiState.value = _uiState.value.copy(savedProfiles = profiles)
        }
    }

    fun dismissProfilesSheet() {
        _uiState.value = _uiState.value.copy(showProfilesSheet = false)
    }

    // --- Contract / Updates Functions ---
    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, checkUpdatesProgress = 0f)
            
            val drifted = repository.getDriftedApps { progress ->
                _uiState.value = _uiState.value.copy(checkUpdatesProgress = progress)
            }
            
            if (drifted.isEmpty()) {
                if (!repository.hasContracts()) {
                    _toastMessage.send("No active optimization contracts found")
                } else {
                    _toastMessage.send("All optimized apps are up to date")
                }
            }
            
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                checkUpdatesProgress = 0f,
                updatesDetected = drifted,
                showUpdatesSheet = drifted.isNotEmpty()
            )
        }
    }
    
    fun dismissUpdatesSheet() {
        _uiState.value = _uiState.value.copy(showUpdatesSheet = false)
    }
    
    suspend fun saveContract(packageName: String, mode: String, isManual: Boolean = true): Boolean {
        val realStatus = ShellHelper.getCompilationFilter(packageName)
        
        if (isManual || ShellHelper.isFilterAchieved(mode, realStatus)) {
            repository.saveContract(packageName, mode, isManual)
            return true
        }
        return false
    }

    fun saveNewAppContract(packageName: String, mode: String) {
        repository.saveContract(packageName, mode, isManual = false)
    }

    fun removeAppContract(packageName: String) {
        repository.removeContract(packageName)
    }

    fun acknowledgeNewApps() {
        val known = loadStringSet(KNOWN_PACKAGES_KEY)
        val pending = loadStringSet(PENDING_NEW_PACKAGES_KEY)
        known.addAll(pending)
        saveStringSet(KNOWN_PACKAGES_KEY, known)
        saveStringSet(PENDING_NEW_PACKAGES_KEY, emptySet())
        _uiState.value = _uiState.value.copy(
            showBatchSheet = false,
            newAppsDetected = emptyList(),
            apps = _uiState.value.apps.map { it.copy(isNew = false) }
        )
    }

    private fun syncPendingNewApps(allInstalledNames: Set<String>): Set<String> {
        if (!prefs.getBoolean(NEW_APPS_TRACKER_READY_KEY, false)) {
            saveStringSet(KNOWN_PACKAGES_KEY, allInstalledNames)
            saveStringSet(PENDING_NEW_PACKAGES_KEY, emptySet())
            prefs.edit().putBoolean(NEW_APPS_TRACKER_READY_KEY, true).apply()
            return emptySet()
        }

        val known = loadStringSet(KNOWN_PACKAGES_KEY)
        val pending = loadStringSet(PENDING_NEW_PACKAGES_KEY)
        pending.addAll(allInstalledNames - known)
        pending.retainAll(allInstalledNames)
        saveStringSet(PENDING_NEW_PACKAGES_KEY, pending)
        return pending
    }

    private fun loadStringSet(key: String): MutableSet<String> {
        return prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value.toSet()).apply()
    }
    
    fun restoreOptimizations() {
        val updates = _uiState.value.updatesDetected
        if (updates.isEmpty()) return
        
        batchJob?.cancel()
        batchJob = viewModelScope.launch(Dispatchers.IO) {
             _uiState.value = _uiState.value.copy(
                isBatchRunning = true, 
                batchProgress = 0f, 
                batchLogs = listOf("> Restoring optimizations...", "> Count: ${updates.size}"),
                showUpdatesSheet = false
            )
            
            updates.forEachIndexed { index, contract ->
                _uiState.value = _uiState.value.copy(
                    batchCurrentApp = contract.packageName,
                    batchCurrentIndex = index + 1,
                    batchTotal = updates.size,
                    batchProgress = (index + 1).toFloat() / updates.size,
                    batchLogs = _uiState.value.batchLogs + "> Restoring: ${contract.packageName} -> ${contract.targetMode}"
                )
                
                val res = ShellHelper.compilePackage(contract.packageName, contract.targetMode)
                
                if (res.isSuccess) {
                    val realStatus = ShellHelper.getCompilationFilter(contract.packageName)
                    if (ShellHelper.isFilterAchieved(contract.targetMode, realStatus)) {
                        repository.saveContract(contract.packageName, contract.targetMode, contract.isManual)
                    }
                }
            }
            
            _uiState.value = _uiState.value.copy(isBatchRunning = false, batchProgress = 1f, batchCurrentApp = "DONE", batchLogs = _uiState.value.batchLogs + "> RESTORE COMPLETE.")
            needsFullRefresh = true
        }
    }

    // --- Existing Core Logic ---

    fun setSortOption(option: SortOption) {
        prefs.edit().putString("sort_option", option.name).apply()
        _uiState.value = _uiState.value.copy(sortOption = option)
        applySorting()
    }

    fun toggleShowAllApps() {
        refreshJob?.cancel()
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
                if (!isActive) return@forEachIndexed
                
                // CHECK FOR MANUAL CONTRACT OVERRIDE
                val existingContract = repository.getContract(pkg)
                val targetMode = if (existingContract != null && existingContract.isManual) {
                    existingContract.targetMode
                } else {
                    mode
                }
                
                val logSuffix = if (targetMode != mode) " [Manual Override]" else ""
                
                val currentLogs = _uiState.value.batchLogs.takeLast(50)
                _uiState.value = _uiState.value.copy(
                    batchCurrentApp = pkg, 
                    batchCurrentIndex = index + 1, 
                    batchProgress = (index + 1).toFloat() / total,
                    batchLogs = currentLogs + "> Compiling: $pkg ($targetMode)$logSuffix"
                )
                
                val sizeBefore = ShellHelper.getArtifactsSizeInKb(pkg)
                
                if (mode == "reset") {
                    val resetRes = ShellHelper.resetPackage(pkg)
                    if (resetRes.isSuccess) {
                        repository.removeContract(pkg)
                    }
                } else {
                    val res = ShellHelper.compilePackage(pkg, targetMode) // Use targetMode
                    if (res.isSuccess) {
                        val realStatus = ShellHelper.getCompilationFilter(pkg)
                        if (ShellHelper.isFilterAchieved(targetMode, realStatus)) {
                            // Preserve manual flag if it existed
                            val isManual = existingContract?.isManual ?: false
                            repository.saveContract(pkg, targetMode, isManual)
                            
                            val logs = _uiState.value.batchLogs + "> [DB] Contract saved: $targetMode"
                            _uiState.value = _uiState.value.copy(batchLogs = logs)
                        }
                    }
                }
                
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
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val showSystem = _uiState.value.showSystemApps
            val showAll = _uiState.value.showAllApps
            
            val currentSystemState = if (showAll) {
                val rawPackages = ShellHelper.getAllSystemPackages()
                val pmList = try {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA).associateBy { it.packageName }
                } catch (e: Exception) { emptyMap() }

                rawPackages.map { pkgName ->
                    pmList[pkgName] ?: ApplicationInfo().apply { packageName = pkgName }
                }
            } else {
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val launchablePackages = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()
                val allInstalled = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                
                allInstalled.filter { info ->
                    val isLaunchable = launchablePackages.contains(info.packageName)
                    val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (showSystem) true else (isLaunchable || isUpdatedSystem)
                }
            }

            val allInstalledNames = try {
                pm.getInstalledApplications(0).map { it.packageName }.toSet()
            } catch (e: Exception) {
                currentSystemState.map { it.packageName }.toSet()
            }
            val pendingNew = syncPendingNewApps(allInstalledNames)

            val mergedList = currentSystemState.map { info ->
                val existing = _uiState.value.apps.find { it.packageName == info.packageName }
                val isReallyNew = pendingNew.contains(info.packageName) && !showAll
                
                if (existing != null && !needsFullRefresh) {
                    existing.copy(isNew = isReallyNew)
                } else {
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

            _uiState.value = _uiState.value.copy(apps = mergedList, isLoading = false)
            applySorting()

            val newlyDetected = mergedList.filter { it.isNew }
            if (newlyDetected.isNotEmpty() && !showAll) {
                _uiState.value = _uiState.value.copy(showBatchSheet = true, newAppsDetected = newlyDetected)
            } else {
                _uiState.value = _uiState.value.copy(showBatchSheet = false, newAppsDetected = newlyDetected)
            }
            
            needsFullRefresh = false

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
