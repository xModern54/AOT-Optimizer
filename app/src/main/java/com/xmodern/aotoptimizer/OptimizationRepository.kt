package com.xmodern.aotoptimizer

import android.content.Context
import org.json.JSONObject

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

    suspend fun getDriftedApps(onProgress: (Float) -> Unit): List<OptimizationContract> {
        val allContracts = prefs.all
        val pm = context.packageManager

        val installedPackages = try {
            pm.getInstalledPackages(0).associateBy { it.packageName }
        } catch (e: Exception) { emptyMap() }

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
            return emptyList()
        }

        onProgress(0.2f)
        val statuses = ShellHelper.scanCompilationStatuses()
        onProgress(0.8f)

        val drifted = mutableListOf<OptimizationContract>()
        validContracts.forEach { contract ->
            val pkgInfo = installedPackages[contract.packageName]
            if (pkgInfo == null) {
                removeContract(contract.packageName)
                return@forEach
            }
            if (pkgInfo.longVersionCode != contract.versionCode) {
                drifted.add(contract)
                return@forEach
            }
            val currentStatus = statuses[contract.packageName]
            if (currentStatus.isNullOrEmpty() || currentStatus == "unknown") return@forEach
            if (!isStatusCompatible(currentStatus, contract.targetMode)) {
                drifted.add(contract)
            }
        }

        onProgress(1f)
        return drifted
    }

    private fun isStatusCompatible(current: String, target: String): Boolean {
        return ShellHelper.isFilterAchieved(target, current)
    }
}