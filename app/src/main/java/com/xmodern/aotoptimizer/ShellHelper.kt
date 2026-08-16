package com.xmodern.aotoptimizer

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShellHelper {

    init {
        Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }

    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        return@withContext Shell.getShell().isRoot
    }

    private val STATUS_REGEX = Regex("""\[status=([^]\s]+)\]""")

    fun compilationFilterRank(filter: String): Int {
        return when (filter) {
            "everything" -> 5
            "speed" -> 4
            "speed-profile" -> 3
            "quicken" -> 2
            "verify" -> 1
            else -> 0
        }
    }

    fun worstCompilationFilter(statuses: Collection<String>): String {
        val known = statuses.filter { it.isNotEmpty() && it != "unknown" }
        if (known.isEmpty()) return "unknown"
        return known.minBy { compilationFilterRank(it) }
    }

    fun isFilterAchieved(target: String, actual: String): Boolean {
        if (actual.isEmpty() || actual == "unknown") return false
        return compilationFilterRank(actual) >= compilationFilterRank(target)
    }

    private fun extractStatuses(line: String): List<String> {
        return STATUS_REGEX.findAll(line).map { it.groupValues[1] }.toList()
    }

    suspend fun getCompilationFilter(packageName: String): String = withContext(Dispatchers.IO) {
        val result = Shell.cmd("dumpsys package $packageName | sed -n '/Dexopt state:/,/^Compiler stats:/p'").exec()
        val statuses = if (result.out.isNotEmpty()) {
            result.out.flatMap { extractStatuses(it) }
        } else {
            val fallback = Shell.cmd("dumpsys package $packageName | grep '\\[status='").exec()
            fallback.out.flatMap { extractStatuses(it) }
        }
        return@withContext worstCompilationFilter(statuses)
    }

    suspend fun getCompilationFilters(packages: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext emptyMap()
        
        val pkgArgs = packages.joinToString(" ")
        val cmd = "dumpsys package $pkgArgs | grep -E 'Package \\[|\\[status='"
        
        val res = Shell.cmd(cmd).exec()
        if (!res.isSuccess) return@withContext emptyMap()
        
        val resultMap = mutableMapOf<String, String>()
        var currentPkg = ""
        val currentStatuses = mutableListOf<String>()
        
        fun flush() {
            if (currentPkg.isNotEmpty()) {
                resultMap[currentPkg] = worstCompilationFilter(currentStatuses)
            }
        }

        res.out.forEach { line ->
            val trim = line.trim()
            if (trim.startsWith("Package [")) {
                flush()
                currentPkg = trim.substringAfter("[").substringBefore("]")
                currentStatuses.clear()
            } else {
                currentStatuses.addAll(extractStatuses(line))
            }
        }
        flush()
        
        return@withContext resultMap
    }

    suspend fun getAllCompilationFilters(): Map<String, String> = withContext(Dispatchers.IO) {
        val res = Shell.cmd("dumpsys package | grep -E 'Package \\[|\\[status='").exec()
        if (!res.isSuccess) return@withContext emptyMap()
        
        val statusesByPkg = mutableMapOf<String, MutableList<String>>()
        var currentPkg = ""
        
        res.out.forEach { line ->
            val trim = line.trim()
            if (trim.startsWith("Package [")) {
                currentPkg = trim.substringAfter("[").substringBefore("]")
            } else if (currentPkg.isNotEmpty()) {
                val found = extractStatuses(line)
                if (found.isNotEmpty()) {
                    statusesByPkg.getOrPut(currentPkg) { mutableListOf() }.addAll(found)
                }
            }
        }
        return@withContext statusesByPkg.mapValues { (_, statuses) -> worstCompilationFilter(statuses) }
    }

    suspend fun compilePackage(packageName: String, mode: String, force: Boolean = false): Shell.Result = withContext(Dispatchers.IO) {
        val forceFlag = if (force) "-f" else ""
        val cmd = "cmd package compile -m $mode $forceFlag $packageName"
        return@withContext Shell.cmd(cmd).exec()
    }
    
    suspend fun resetPackage(packageName: String): Shell.Result = withContext(Dispatchers.IO) {
        return@withContext "cmd package compile --reset $packageName".run { Shell.cmd(this).exec() }
    }

    suspend fun getAllPackages(): List<String> = withContext(Dispatchers.IO) {
        val res = Shell.cmd("pm list packages").exec()
        return@withContext if (res.isSuccess) res.out.map { it.substringAfter("package:") } else emptyList()
    }

    suspend fun getAllSystemPackages(): List<String> = withContext(Dispatchers.IO) {
        // GOD MODE: Fetch absolutely everything
        val res = Shell.cmd("pm list packages").exec()
        return@withContext if (res.isSuccess) res.out.map { it.substringAfter("package:") } else emptyList()
    }

    suspend fun getArtifactsSize(packageName: String): String = withContext(Dispatchers.IO) {
        val pathResult = Shell.cmd("pm path $packageName").exec()
        if (!pathResult.isSuccess || pathResult.out.isEmpty()) return@withContext "0 B"
        val apkPath = pathResult.out[0].substringAfter("package:")
        val cmd = """
            APK_PATH="$apkPath"
            APP_DIR=$(dirname "${'$'}APK_PATH")
            if [ -d "${'$'}APP_DIR/oat" ]; then
                du -sh "${'$'}APP_DIR/oat" | cut -f1
            elif ls "${'$'}APP_DIR"/*.odex >/dev/null 2>&1; then
                du -shc "${'$'}APP_DIR"/*.odex "${'$'}APP_DIR"/*.vdex 2>/dev/null | tail -n1 | cut -f1
            else
                REL_PATH="${'$'}{APK_PATH#/}"
                MANGLED_NAME="${'$'}{REL_PATH//\//@}"
                SIZE=$(du -shc /data/dalvik-cache/*/"${'$'}MANGLED_NAME"@classes*.{dex,vdex,art} 2>/dev/null | tail -n1 | cut -f1)
                echo "${'$'}{SIZE:-0 B}"
            fi
        """.trimIndent()
        val res = Shell.cmd(cmd).exec()
        return@withContext if (res.isSuccess && res.out.isNotEmpty()) res.out[0].trim() else "0 B"
    }

    suspend fun getArtifactsSizeInKb(packageName: String): Long = withContext(Dispatchers.IO) {
        val pathResult = Shell.cmd("pm path $packageName").exec()
        if (!pathResult.isSuccess || pathResult.out.isEmpty()) return@withContext 0L
        val apkPath = pathResult.out[0].substringAfter("package:")
        val cmd = """
            APK_PATH="$apkPath"
            APP_DIR=$(dirname "${'$'}APK_PATH")
            if [ -d "${'$'}APP_DIR/oat" ]; then
                du -sk "${'$'}APP_DIR/oat" | cut -f1
            elif ls "${'$'}APP_DIR"/*.odex >/dev/null 2>&1; then
                du -skc "${'$'}APP_DIR"/*.odex "${'$'}APP_DIR"/*.vdex 2>/dev/null | tail -n1 | cut -f1
            else
                REL_PATH="${'$'}{APK_PATH#/}"
                MANGLED_NAME="${'$'}{REL_PATH//\//@}"
                du -skc /data/dalvik-cache/*/"${'$'}MANGLED_NAME"@classes*.{dex,vdex,art} 2>/dev/null | tail -n1 | cut -f1
            fi
        """.trimIndent()
        val res = Shell.cmd(cmd).exec()
        if (res.isSuccess && res.out.isNotEmpty()) {
            return@withContext res.out[0].trim().toLongOrNull() ?: 0L
        }
        return@withContext 0L
    }

    fun getDexSizeInMb(packageName: String): Int {
        // ... (existing)
        val pathRes = Shell.cmd("pm path $packageName").exec()
        if (!pathRes.isSuccess || pathRes.out.isEmpty()) return 0
        val apkPath = pathRes.out[0].substringAfter("package:")
        
        val res = Shell.cmd("unzip -l \"$apkPath\" | grep \"classes.*\\.dex\" | awk '{s+=\$1} END {print s}'").exec()
        if (!res.isSuccess || res.out.isEmpty()) return 0
        
        val bytes = res.out[0].trim().toLongOrNull() ?: 0L
        return (bytes / (1024 * 1024)).toInt()
    }

    // --- BOOT SCRIPT LOGIC ---

    private const val BOOT_SCRIPT_PATH = "/data/adb/service.d/00_kill_dexopt.sh"
    private const val BOOT_LOG_PATH = "/cache/aot_killer.log"
    private const val BOOT_SCRIPT_CONTENT = """#!/system/bin/sh
LOG="/cache/aot_killer.log"
log() {
    echo "${'$'}(date) ${'$'}*" >> "${'$'}LOG"
}
log "=== aot killer start pid=${'$'}${'$'} ==="
log "sys.boot_completed=${'$'}(getprop sys.boot_completed)"
if setprop pm.dexopt.disable_bg_dexopt true; then
    log "setprop pm.dexopt.disable_bg_dexopt true: ok now=${'$'}(getprop pm.dexopt.disable_bg_dexopt)"
else
    log "setprop pm.dexopt.disable_bg_dexopt true: FAILED rc=${'$'}?"
fi
sleep 1
while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done
log "boot completed, running bg-dexopt-job --disable"
if cmd package bg-dexopt-job --disable >> "${'$'}LOG" 2>&1; then
    log "bg-dexopt-job --disable: ok"
else
    log "bg-dexopt-job --disable: FAILED rc=${'$'}?"
fi
log "final pm.dexopt.disable_bg_dexopt=${'$'}(getprop pm.dexopt.disable_bg_dexopt)"
log "=== aot killer done ==="
"""

    private fun appendBootLog(message: String) {
        val escaped = message.replace("\"", "'")
        Shell.cmd("echo \"\$(date) $escaped\" >> $BOOT_LOG_PATH").exec()
    }

    suspend fun isSystemDexOptDisabled(): Boolean = withContext(Dispatchers.IO) {
        val propRes = Shell.cmd("getprop pm.dexopt.disable_bg_dexopt").exec()
        val propDisabled = propRes.out.firstOrNull()?.trim().equals("true", ignoreCase = true)

        val jobRes = Shell.cmd("dumpsys jobscheduler | grep 'JOB.*BackgroundDexoptJobService'").exec()
        val jobAbsent = jobRes.out.none { it.contains("JOB") && it.contains("BackgroundDexoptJobService") }

        return@withContext propDisabled && jobAbsent
    }

    suspend fun enableSystemDexOpt(): Boolean = withContext(Dispatchers.IO) {
        val rmRes = Shell.cmd("rm $BOOT_SCRIPT_PATH").exec()
        if (rmRes.isSuccess) {
            appendBootLog("app: removed $BOOT_SCRIPT_PATH")
        } else {
            appendBootLog("app: rm $BOOT_SCRIPT_PATH FAILED out=${rmRes.out.joinToString(" ")}")
        }
        val propRes = Shell.cmd("setprop pm.dexopt.disable_bg_dexopt false").exec()
        appendBootLog(
            if (propRes.isSuccess) "app: setprop pm.dexopt.disable_bg_dexopt false: ok"
            else "app: setprop pm.dexopt.disable_bg_dexopt false: FAILED"
        )
        val res = Shell.cmd("cmd package bg-dexopt-job --enable").exec()
        appendBootLog(
            if (res.isSuccess) "app: bg-dexopt-job --enable: ok"
            else "app: bg-dexopt-job --enable: FAILED out=${res.out.joinToString(" ")}"
        )
        return@withContext res.isSuccess
    }

    suspend fun disableSystemDexOpt(): Boolean = withContext(Dispatchers.IO) {
        val propRes = Shell.cmd("setprop pm.dexopt.disable_bg_dexopt true").exec()
        appendBootLog(
            if (propRes.isSuccess) "app: setprop pm.dexopt.disable_bg_dexopt true: ok"
            else "app: setprop pm.dexopt.disable_bg_dexopt true: FAILED"
        )
        val res = Shell.cmd("cmd package bg-dexopt-job --disable").exec()
        appendBootLog(
            if (res.isSuccess) "app: bg-dexopt-job --disable: ok"
            else "app: bg-dexopt-job --disable: FAILED out=${res.out.joinToString(" ")}"
        )
        val scriptCmd = """
cat > $BOOT_SCRIPT_PATH << 'EOF'
$BOOT_SCRIPT_CONTENT
EOF
chmod 755 $BOOT_SCRIPT_PATH
""".trimIndent()
        val writeRes = Shell.cmd(scriptCmd).exec()
        if (writeRes.isSuccess) {
            appendBootLog("app: wrote $BOOT_SCRIPT_PATH")
        } else {
            appendBootLog("app: write $BOOT_SCRIPT_PATH FAILED out=${writeRes.out.joinToString(" ")}")
        }
        return@withContext res.isSuccess
    }

    suspend fun getAppFramework(packageName: String): String = withContext(Dispatchers.IO) {
        val pathResult = Shell.cmd("pm path $packageName").exec()
        if (!pathResult.isSuccess || pathResult.out.isEmpty()) return@withContext "Native"
        val apkPath = pathResult.out[0].substringAfter("package:")
        val appDir = apkPath.substringBeforeLast("/")
        val cmd = """
            APP_DIR="$appDir"
            if ls "${'$'}APP_DIR"/lib/*/libflutter.so >/dev/null 2>&1; then echo "Flutter";
            elif ls "${'$'}APP_DIR"/lib/*/libapp.so >/dev/null 2>&1 && ls "${'$'}APP_DIR"/lib/*/libflutter.so >/dev/null 2>&1; then echo "Flutter";
            elif ls "${'$'}APP_DIR"/lib/*/libreactnative.so >/dev/null 2>&1; then echo "React Native";
            elif ls "${'$'}APP_DIR"/lib/*/libhermes.so >/dev/null 2>&1; then echo "React Native"; 
            else echo "Native"; fi
        """.trimIndent()
        val res = Shell.cmd(cmd).exec()
        return@withContext if (res.isSuccess && res.out.isNotEmpty()) res.out[0].trim() else "Native"
    }
}
