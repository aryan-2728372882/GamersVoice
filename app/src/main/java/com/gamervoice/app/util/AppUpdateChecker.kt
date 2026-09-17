package com.gamervoice.app.util

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.gamervoice.app.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"
    private const val VERSION_API_URL = "https://gamersvoice.onrender.com/api/app-version"
    private const val DEFAULT_DOWNLOAD_URL = "https://gamersvoice.onrender.com/gamervoice-release.apk"
    private const val WEBSITE_DOWNLOAD_URL = "https://gamersvoice.onrender.com/download"
    private const val PREFS_NAME = "gv_update_prefs"
    private const val KEY_LAST_CHECK = "last_check_timestamp"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks for updates once per day automatically on app launch.
     */
    fun checkForUpdate(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCheck < ONE_DAY_MS) {
            Log.d(TAG, "Update check skipped: already checked within the last 24 hours.")
            return
        }

        val request = Request.Builder()
            .url(VERSION_API_URL)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Update check failed: ${e.message}")
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

                    // Mark successful daily check
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

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
            .setPositiveButton("Download & Install") { _, _ ->
                startDownloadAndInstall(activity, downloadUrl)
            }
            .setNeutralButton("Visit Website") { _, _ ->
                openWebsiteDownload(activity)
            }

        if (!mandatory) {
            builder.setNegativeButton(activity.getString(R.string.dialog_update_later), null)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun openWebsiteDownload(activity: Activity) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_DOWNLOAD_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open download website", e)
            Toast.makeText(activity, "Unable to open website link", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun startDownloadAndInstall(activity: Activity, downloadUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, activity.getString(R.string.toast_allow_install_unknown), Toast.LENGTH_LONG).show()
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(permIntent)
                return
            }
        }

        val progressDialog = ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.dialog_downloading_update))
            setMessage(activity.getString(R.string.dialog_downloading_msg))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            isIndeterminate = false
            setCancelable(false)
            show()
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.toast_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post {
                        progressDialog.dismiss()
                        Toast.makeText(activity, activity.getString(R.string.toast_download_server_error, response.code), Toast.LENGTH_LONG).show()
                    }
                    return
                }

                val body = response.body ?: return
                val contentLength = body.contentLength()

                try {
                    val destDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                    val apkFile = File(destDir, "gamervoice_update.apk")
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                if (contentLength > 0) {
                                    val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                    mainHandler.post {
                                        progressDialog.progress = progress
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    mainHandler.post {
                        progressDialog.dismiss()
                        launchApkInstaller(activity, apkFile)
                    }

                } catch (e: Exception) {
                    mainHandler.post {
                        progressDialog.dismiss()
                        Toast.makeText(activity, activity.getString(R.string.toast_update_save_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun launchApkInstaller(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, context.getString(R.string.toast_installer_start_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
