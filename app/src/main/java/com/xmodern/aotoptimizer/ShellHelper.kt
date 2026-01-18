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

    suspend fun getCompilationFilter(packageName: String): String = withContext(Dispatchers.IO) {
        val result = Shell.cmd("dumpsys package $packageName | grep 'status=' | head -n 1").exec()
        if (result.isSuccess && result.out.isNotEmpty()) {
            val line = result.out[0]
            val match = Regex("status=([^]]+)").find(line)
            return@withContext match?.groupValues?.get(1) ?: "unknown"
        }
        return@withContext "unknown"
    }

    suspend fun compilePackage(packageName: String, mode: String): Shell.Result = withContext(Dispatchers.IO) {
        // Removed -f to avoid forcing recompilation if not needed
        val cmd = "cmd package compile -m $mode $packageName"
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
    private const val BOOT_SCRIPT_CONTENT = """#!/system/bin/sh
LOG="/cache/aot_killer.log"
sleep 1
while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done
cmd package bg-dexopt-job --disable >> ${'$'}LOG 2>&1
"""

    suspend fun isSystemDexOptDisabled(): Boolean = withContext(Dispatchers.IO) {
        // Check if the JOB exists in scheduler.
        // grep returns exit code 1 if NOT found (which means Disabled).
        // So we just check if the output contains the job ID string.
        val res = Shell.cmd("dumpsys jobscheduler | grep 'JOB.*BackgroundDexoptJobService'").exec()
        // If output is empty, it means grep found nothing -> JOB IS DISABLED
        return@withContext res.out.isEmpty()
    }

    suspend fun enableSystemDexOpt(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("rm $BOOT_SCRIPT_PATH").exec()
        val res = Shell.cmd("cmd package bg-dexopt-job --enable").exec()
        return@withContext res.isSuccess
    }

    suspend fun disableSystemDexOpt(): Boolean = withContext(Dispatchers.IO) {
        // 1. Create Script
        val scriptCmd = "echo '$BOOT_SCRIPT_CONTENT' > $BOOT_SCRIPT_PATH && chmod 755 $BOOT_SCRIPT_PATH"
        Shell.cmd(scriptCmd).exec()
        
        // 2. Execute immediately
        val res = Shell.cmd("cmd package bg-dexopt-job --disable").exec()
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
