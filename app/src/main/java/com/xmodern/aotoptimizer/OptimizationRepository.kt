package com.xmodern.aotoptimizer

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- DATA MODEL ---
data class OptimizationContract(
    val packageName: String,
    val targetMode: String,     // e.g., "speed", "everything"
    val versionCode: Long,      // Unique version ID to detect updates
    val timestamp: Long,        // When we last optimized it
    val isManual: Boolean = false // Track if manually optimized by user
)

// --- REPOSITORY ---
class OptimizationRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("aot_contracts_db", Context.MODE_PRIVATE)

    /**
     * Saves or Updates a contract for a specific app.
     * Called when we successfully compile an app.
     */
    fun saveContract(packageName: String, mode: String, isManual: Boolean) {
        try {
            val pkgInfo = context.packageManager.getPackageInfo(packageName, 0)
            val versionCode = pkgInfo.longVersionCode 
            
            val contract = OptimizationContract(
                packageName = packageName,
                targetMode = mode,
                versionCode = versionCode,
                timestamp = System.currentTimeMillis(),
                isManual = isManual
            )
            
            val json = JSONObject().apply {
                put("p", contract.packageName)
                put("m", contract.targetMode)
                put("v", contract.versionCode)
                put("t", contract.timestamp)
                put("man", contract.isManual) // Save manual flag
            }
            
            prefs.edit().putString(packageName, json.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeContract(packageName: String) {
        prefs.edit().remove(packageName).apply()
    }

    fun hasContracts(): Boolean {
        return prefs.all.isNotEmpty()
    }

    fun getContract(packageName: String): OptimizationContract? {
        val jsonString = prefs.getString(packageName, null) ?: return null
        return try {
            val json = JSONObject(jsonString)
            OptimizationContract(
                packageName = json.getString("p"),
                targetMode = json.getString("m"),
                versionCode = json.getLong("v"),
                timestamp = json.optLong("t", 0L),
                isManual = json.optBoolean("man", false)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    fun getAllContracts(): List<OptimizationContract> {
        val list = mutableListOf<OptimizationContract>()
        for ((_, value) in prefs.all) {
            if (value is String) {
                try {
                    val json = JSONObject(value)
                    list.add(OptimizationContract(
                        packageName = json.getString("p"),
                        targetMode = json.getString("m"),
                        versionCode = json.getLong("v"),
                        timestamp = json.optLong("t", 0L),
                        isManual = json.optBoolean("man", false) // Default to false for old contracts
                    ))
                } catch (e: Exception) { /* Ignore */ }
            }
        }
        return list.sortedBy { it.packageName }
    }

    /**
     * The Heavy Lifter: Scans all contracts using Parallel Async Requests.
     * RELIABLE method (Slow but accurate). Includes Progress Callback.
     */
    suspend fun getDriftedApps(onProgress: (Float) -> Unit): List<OptimizationContract> = coroutineScope {
        val allContracts = prefs.all
        val pm = context.packageManager

        // 1. Bulk Fetch PM Data
        val installedPackages = try {
            pm.getInstalledPackages(0).associateBy { it.packageName }
        } catch (e: Exception) { emptyMap() }
        
        // 2. Parse contracts
        val validContracts = mutableListOf<OptimizationContract>()
        for ((_, value) in allContracts) {
            if (value !is String) continue
            try {
                val json = JSONObject(value)
                validContracts.add(OptimizationContract(
                    packageName = json.getString("p"),
                    targetMode = json.getString("m"),
                    versionCode = json.getLong("v"),
                    timestamp = json.optLong("t", 0L),
                    isManual = json.optBoolean("man", false)
                ))
            } catch (e: Exception) {}
        }

        if (validContracts.isEmpty()) {
            onProgress(1f)
            return@coroutineScope emptyList()
        }

        // 3. Process in Chunks (Async)
        val driftedList = mutableListOf<OptimizationContract>()
        var processedCount = 0
        
        // Chunk 15 is a good balance for LibSU
        validContracts.chunked(15).forEach { batch ->
            val deferred = batch.map { contract ->
                async(Dispatchers.IO) {
                    val pkgInfo = installedPackages[contract.packageName]
                    
                    if (pkgInfo == null) {
                        removeContract(contract.packageName)
                        return@async null
                    }

                    // Version Drift
                    if (pkgInfo.longVersionCode != contract.versionCode) {
                        return@async contract
                    }

                    // Status Drift (Reliable Query)
                    val currentStatus = ShellHelper.getCompilationFilter(contract.packageName)
                    
                    if (currentStatus == "unknown" || currentStatus.isEmpty()) return@async null
                    
                    if (!isStatusCompatible(currentStatus, contract.targetMode)) {
                        return@async contract
                    }
                    
                    null
                }
            }
            driftedList.addAll(deferred.awaitAll().filterNotNull())
            
            processedCount += batch.size
            onProgress(processedCount.toFloat() / validContracts.size)
        }
        
        return@coroutineScope driftedList
    }

    private fun isStatusCompatible(current: String, target: String): Boolean {
        return ShellHelper.isFilterAchieved(target, current)
    }
}