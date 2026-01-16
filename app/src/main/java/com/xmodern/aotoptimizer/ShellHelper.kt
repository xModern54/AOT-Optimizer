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
        val cmd = "cmd package compile -m $mode -f $packageName"
        return@withContext Shell.cmd(cmd).exec()
    }
    
    suspend fun resetPackage(packageName: String): Shell.Result = withContext(Dispatchers.IO) {
        return@withContext "cmd package compile --reset $packageName".run { Shell.cmd(this).exec() }
    }

    suspend fun getAllPackages(): List<String> = withContext(Dispatchers.IO) {
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
