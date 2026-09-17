package com.gamervoice.app.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.gamervoice.app.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"
    private const val VERSION_API_URL = "https://gamersvoice.onrender.com/api/app-version"
    private const val DEFAULT_DOWNLOAD_URL = "https://gamersvoice.onrender.com/download"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkForUpdate(activity: Activity, manualCheck: Boolean = false) {
        val request = Request.Builder()
            .url(VERSION_API_URL)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Update check failed: ${e.message}")
                if (manualCheck) {
                    mainHandler.post {
                        Toast.makeText(activity, activity.getString(R.string.toast_update_server_unreachable), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: return
                try {
                    val json = JSONObject(bodyStr)
                    val serverVersionCode = json.optInt("latestVersionCode", 0)
                    val serverVersionName = json.optString("latestVersionName", "Latest")
                    var downloadUrl = json.optString("downloadUrl", DEFAULT_DOWNLOAD_URL)
                    if (downloadUrl.startsWith("/")) {
                        downloadUrl = "https://gamersvoice.onrender.com$downloadUrl"
                    }
                    val changelog = json.optString("changelog", "Performance improvements and bug fixes.")
                    val mandatory = json.optBoolean("mandatory", false)

                    val pInfo = try {
                        activity.packageManager.getPackageInfo(activity.packageName, 0)
                    } catch (_: Exception) { null }
                    @Suppress("DEPRECATION")
                    val currentCode = pInfo?.versionCode ?: 1
                    val currentName = pInfo?.versionName ?: "1.0.0"
                    Log.i(TAG, "Installed: $currentCode ($currentName), Server: $serverVersionCode ($serverVersionName)")

                    if (serverVersionCode > currentCode) {
                        mainHandler.post {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                showUpdateDialog(activity, serverVersionName, changelog, downloadUrl, mandatory)
                            }
                        }
                    } else if (manualCheck) {
                        mainHandler.post {
                            Toast.makeText(activity, activity.getString(R.string.toast_app_up_to_date, currentName), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing update check response", e)
                }
            }
        })
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        changelog: String,
        downloadUrl: String,
        mandatory: Boolean
    ) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_update_title, versionName))
            .setMessage(activity.getString(R.string.dialog_update_msg, changelog))
            .setPositiveButton(activity.getString(R.string.dialog_update_now)) { _, _ ->
                redirectToStoreOrDownload(activity, downloadUrl)
            }

        if (!mandatory) {
            builder.setNegativeButton(activity.getString(R.string.dialog_update_later), null)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun redirectToStoreOrDownload(activity: Activity, downloadUrl: String) {
        val playStoreUri = Uri.parse("market://details?id=${activity.packageName}")
        val playStoreIntent = Intent(Intent.ACTION_VIEW, playStoreUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            activity.startActivity(playStoreIntent)
        } catch (_: Exception) {
            // Fallback to web browser (official site or web play store)
            val webUri = if (downloadUrl.startsWith("http")) {
                Uri.parse(downloadUrl)
            } else {
                Uri.parse("https://gamersvoice.onrender.com/download")
            }
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, webUri))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open download link", e)
                Toast.makeText(activity, "Unable to open update link", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
