package com.acmods.lagkiller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject

class AppActionController(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    private val CORE_PROTECTED = setOf(
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

    fun forceStop(pkg: String): String {
        if (CORE_PROTECTED.contains(pkg)) {
            return JSONObject().apply {
                put("success", false)
                put("message", "لا يمكن إيقاف تطبيق نظام أساسي محمي")
            }.toString()
        }
        RootShell.exec("am force-stop $pkg")
        return JSONObject().apply {
            put("success", true)
            put("message", "تم إيقاف التطبيق فورا")
        }.toString()
    }

    fun suspendApp(pkg: String): String {
        if (CORE_PROTECTED.contains(pkg)) {
            return JSONObject().apply {
                put("success", false)
                put("message", "لا يمكن تعليق تطبيق نظام أساسي محمي")
            }.toString()
        }
        RootShell.exec("pm suspend $pkg && am force-stop $pkg")
        return JSONObject().apply {
            put("success", true)
            put("message", "تم تعليق التطبيق (Suspend) وإيقافه")
        }.toString()
    }

    fun unsuspendApp(pkg: String): String {
        RootShell.exec("pm unsuspend $pkg")
        return JSONObject().apply {
            put("success", true)
            put("message", "تم استئناف التطبيق بنجاح")
        }.toString()
    }

    fun freezeApp(pkg: String): String {
        if (CORE_PROTECTED.contains(pkg)) {
            return JSONObject().apply {
                put("success", false)
                put("message", "لا يمكن تجميد تطبيق نظام أساسي محمي")
            }.toString()
        }
        RootShell.exec("pm disable-user --user 0 $pkg && am force-stop $pkg")
        return JSONObject().apply {
            put("success", true)
            put("message", "تم تجميد وتعطيل التطبيق بالكامل")
        }.toString()
    }

    fun unfreezeApp(pkg: String): String {
        RootShell.exec("pm enable $pkg")
        return JSONObject().apply {
            put("success", true)
            put("message", "تم فك التجميد وتفعيل التطبيق")
        }.toString()
    }

    fun quickBoost(): String {
        // Collect all running non-protected user apps
        val psOutput = RootShell.exec("ps -A -o NAME | grep -v 'com.android.systemui' | grep -v 'com.android.launcher3' | grep -v 'com.acmods.lagkiller'")
        val packagesToKill = mutableSetOf<String>()

        for (line in psOutput.lines()) {
            val name = line.trim().substringBefore(":")
            if (name.contains(".") && !name.startsWith("/")) {
                if (!CORE_PROTECTED.contains(name)) {
                    try {
                        val appInfo = packageManager.getApplicationInfo(name, 0)
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        if (!isSystem) {
                            packagesToKill.add(name)
                        }
                    } catch (e: Exception) {
                        // Ignore unknown packages
                    }
                }
            }
        }

        var killedCount = 0
        for (pkg in packagesToKill) {
            RootShell.exec("am force-stop $pkg")
            killedCount++
        }

        // Drop memory caches for instant boost
        RootShell.exec("echo 3 > /proc/sys/vm/drop_caches")

        return JSONObject().apply {
            put("success", true)
            put("killedCount", killedCount)
            put("message", "تم إيقاف $killedCount تطبيق بالخلفية وتنظيف كاش الذاكرة بنجاح ⚡")
        }.toString()
    }
}
