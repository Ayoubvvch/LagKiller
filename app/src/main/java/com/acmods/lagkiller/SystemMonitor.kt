package com.acmods.lagkiller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SystemMonitor(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val cacheFile = File(context.filesDir, "apps_metadata_cache.json")
    private val lastStatsFile = File(context.filesDir, "last_system_stats.json")

    private val CORE_PROTECTED_PACKAGES = mutableSetOf(
        "android",
        "com.android.systemui",
        "com.android.launcher3",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.dialer",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.topjohnwu.magisk",
        "io.github.a13e300.ksuwebui",
        "bin.mt.termex",
        "com.termux",
        "com.acmods.lagkiller"
    )

    data class CachedAppMeta(
        val pkg: String,
        val name: String,
        val isSystem: Boolean,
        val isProtected: Boolean
    )

    private val cachedApps = mutableListOf<CachedAppMeta>()
    private var isAppListCached = false

    init {
        try {
            val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!defaultIme.isNullOrEmpty()) {
                val imePkg = defaultIme.substringBefore("/")
                CORE_PROTECTED_PACKAGES.add(imePkg)
            }
        } catch (e: Exception) {
            // Ignore
        }
        loadDiskCache()
    }

    private fun loadDiskCache() {
        if (cacheFile.exists()) {
            try {
                val text = cacheFile.readText()
                val array = JSONArray(text)
                cachedApps.clear()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    cachedApps.add(
                        CachedAppMeta(
                            obj.getString("pkg"),
                            obj.getString("name"),
                            obj.getBoolean("isSystem"),
                            obj.getBoolean("isProtected")
                        )
                    )
                }
                isAppListCached = cachedApps.isNotEmpty()
            } catch (e: Exception) {
                isAppListCached = false
            }
        }
    }

    private fun saveDiskCache() {
        try {
            val array = JSONArray()
            for (app in cachedApps) {
                val obj = JSONObject().apply {
                    put("pkg", app.pkg)
                    put("name", app.name)
                    put("isSystem", app.isSystem)
                    put("isProtected", app.isProtected)
                }
                array.put(obj)
            }
            cacheFile.writeText(array.toString())
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun getLastSavedStats(): String {
        return if (lastStatsFile.exists()) {
            try { lastStatsFile.readText() } catch (e: Exception) { "{}" }
        } else {
            "{}"
        }
    }

    @Synchronized
    fun ensureAppCache(forceRefresh: Boolean = false) {
        if (!forceRefresh && isAppListCached && cachedApps.isNotEmpty()) return
        try {
            val installed = packageManager.getInstalledApplications(0)
            cachedApps.clear()
            for (appInfo in installed) {
                val pkg = appInfo.packageName
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isProtected = CORE_PROTECTED_PACKAGES.contains(pkg)
                val label = try {
                    appInfo.loadLabel(packageManager).toString()
                } catch (e: Exception) {
                    pkg
                }
                cachedApps.add(CachedAppMeta(pkg, label, isSystem, isProtected))
            }
            isAppListCached = true
            saveDiskCache()
        } catch (e: Exception) {
            // Fallback
        }
    }

    data class AppProcData(
        var cpuPercent: Double = 0.0,
        var ramKB: Long = 0,
        var procCount: Int = 0
    )

    fun getSystemStats(): String {
        val root = JSONObject()

        // 1. RAM Info directly from /proc/meminfo (~1ms)
        var totalRamKB = 0L
        var availRamKB = 0L
        try {
            val memInfoLines = File("/proc/meminfo").readLines()
            for (line in memInfoLines) {
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim()
                    val valKB = parts[1].trim().split("\\s+".toRegex())[0].toLongOrNull() ?: 0L
                    if (key == "MemTotal") totalRamKB = valKB
                    else if (key == "MemAvailable") availRamKB = valKB
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        val totalRamMB = (totalRamKB / 1024).toInt()
        val availRamMB = (availRamKB / 1024).toInt()
        val usedRamMB = (totalRamMB - availRamMB).coerceAtLeast(0)
        val ramPercent = if (totalRamMB > 0) ((usedRamMB.toDouble() / totalRamMB) * 100).toInt() else 0

        val ramObj = JSONObject().apply {
            put("totalMB", totalRamMB)
            put("usedMB", usedRamMB)
            put("freeMB", availRamMB)
            put("percent", ramPercent)
        }
        root.put("ram", ramObj)

        // 2. CPU and Process Parsing via single fast Root top command
        val runningAppsMap = mutableMapOf<String, AppProcData>()
        var totalCpuUsed = 0.0
        var totalCpuUser = 0.0
        var totalCpuSys = 0.0

        val topOutput = RootShell.exec("top -b -n 1 -m 35")
        for (line in topOutput.lines()) {
            val trimmed = line.trim()
            if (trimmed.contains("%cpu", ignoreCase = true)) {
                val parts = trimmed.split("\\s+".toRegex())
                for (p in parts) {
                    if (p.endsWith("%user")) totalCpuUser = p.removeSuffix("%user").toDoubleOrNull() ?: 0.0
                    if (p.endsWith("%sys")) totalCpuSys = p.removeSuffix("%sys").toDoubleOrNull() ?: 0.0
                }
                totalCpuUsed = totalCpuUser + totalCpuSys
            } else if (trimmed.matches("^\\d+.*".toRegex())) {
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 10) {
                    val cpuIdx = if (parts.size > 11) 8 else 6
                    val argsIdx = parts.size - 1
                    val cpuVal = parts[cpuIdx].toDoubleOrNull() ?: 0.0
                    val fullProcName = parts[argsIdx]
                    val pkgName = fullProcName.substringBefore(":")

                    val data = runningAppsMap.getOrPut(pkgName) { AppProcData() }
                    data.cpuPercent += cpuVal
                    data.procCount += 1
                }
            }
        }

        // Fast ps scan for accurate RAM
        val psOutput = RootShell.exec("ps -A -o RSS,NAME")
        for (line in psOutput.lines()) {
            val trimmed = line.trim()
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val rssKB = parts[0].toLongOrNull() ?: 0L
                val fullProcName = parts[1]
                val pkgName = fullProcName.substringBefore(":")
                val data = runningAppsMap[pkgName]
                if (data != null) {
                    data.ramKB += rssKB
                } else if (fullProcName.contains(".") && !fullProcName.startsWith("/")) {
                    runningAppsMap[pkgName] = AppProcData(0.0, rssKB, 1)
                }
            }
        }

        val cpuObj = JSONObject().apply {
            put("total", String.format("%.1f", totalCpuUsed).toDouble())
            put("user", String.format("%.1f", totalCpuUser).toDouble())
            put("sys", String.format("%.1f", totalCpuSys).toDouble())
        }
        root.put("cpu", cpuObj)

        // 3. Match with cached installed apps (Instant in-memory lookup)
        ensureAppCache()
        val appsArray = JSONArray()

        var lagMakersCount = 0
        var runningCount = 0
        var frozenCount = 0

        for (meta in cachedApps) {
            val pkg = meta.pkg
            val isProtected = meta.isProtected
            val isSystem = meta.isSystem

            val procData = runningAppsMap[pkg]
            val isRunning = procData != null && procData.procCount > 0
            if (isRunning) runningCount++

            val cpu = procData?.cpuPercent ?: 0.0
            val ramMB = if (procData != null) (procData.ramKB / 1024).toInt() else 0
            val procs = procData?.procCount ?: 0

            val isLagMaker = isRunning && !isProtected && (cpu >= 8.0 || ramMB >= 400)
            if (isLagMaker) lagMakersCount++

            var isDisabled = false
            var isSuspended = false
            if (!isRunning) {
                try {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    isDisabled = !appInfo.enabled
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isSuspended = packageManager.isPackageSuspended(pkg)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            if (isDisabled || isSuspended) {
                frozenCount++
            }

            val appObj = JSONObject().apply {
                put("pkg", pkg)
                put("name", meta.name)
                put("cpu", String.format("%.1f", cpu).toDouble())
                put("ram", ramMB)
                put("procs", procs)
                put("isRunning", isRunning)
                put("isLagMaker", isLagMaker)
                put("isSuspended", isSuspended)
                put("isDisabled", isDisabled)
                put("isSystem", isSystem)
                put("isProtected", isProtected)
            }
            appsArray.put(appObj)
        }

        root.put("lagMakersCount", lagMakersCount)
        root.put("runningCount", runningCount)
        root.put("frozenCount", frozenCount)
        root.put("apps", appsArray)

        val jsonStr = root.toString()
        // Save snapshot to disk cache for instant frame-0 loading on next cold start
        try { lastStatsFile.writeText(jsonStr) } catch (e: Exception) {}

        return jsonStr
    }
}
