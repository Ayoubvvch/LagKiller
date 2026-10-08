package com.acmods.lagkiller

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object RootShell {
    private const val TAG = "RootShell"

    private val suBinaryCandidates = arrayOf(
        "su",
        "/data/adb/magisk/magisk su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su"
    )

    private var activeSuPrefix: String? = null

    fun isRootAvailable(): Boolean {
        if (activeSuPrefix != null) return true
        for (candidate in suBinaryCandidates) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$candidate -c id"))
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                val output = reader.readLine() ?: ""
                p.waitFor()
                if (output.contains("uid=0")) {
                    activeSuPrefix = candidate
                    Log.i(TAG, "Root detected using: $candidate")
                    return true
                }
            } catch (e: Exception) {
                // Ignore and try next candidate
            }
        }
        return false
    }

    fun exec(command: String): String {
        val su = activeSuPrefix ?: if (isRootAvailable()) activeSuPrefix!! else "su"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$su -c \"$command\""))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: $command", e)
            ""
        }
    }
}
