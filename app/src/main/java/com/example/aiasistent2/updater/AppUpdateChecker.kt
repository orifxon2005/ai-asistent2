package com.example.aiasistent2.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AppUpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateChecker"
        private const val GITHUB_REPO = "orifxon2005/ai-asistent2"
        const val UPDATE_URL = "https://raw.githubusercontent.com/$GITHUB_REPO/main/update/version.json"
        const val APK_DOWNLOAD_URL = "https://github.com/$GITHUB_REPO/releases/latest/download/app-release.apk"
        const val APK_FILE_NAME = "update.apk"

        // Bir xil versiyani qayta-qayta yuklamaslik uchun
        private const val PREF_UPDATE = "update_prefs"
        private const val KEY_LAST_ATTEMPTED = "last_attempted_version_code"
    }

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val changeLog: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(UPDATE_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val remoteVersionCode = json.getInt("versionCode")
            val remoteVersionName = json.getString("versionName")
            val downloadUrl = json.optString("downloadUrl", APK_DOWNLOAD_URL)
            val changeLog = json.optString("changeLog", "Bug fixes and improvements")

            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }

            // Yangi versiya bor va uni avval urinib ko'rmagan bo'lsak
            if (remoteVersionCode > currentVersionCode) {
                val prefs = context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
                val lastAttempted = prefs.getInt(KEY_LAST_ATTEMPTED, 0)

                if (lastAttempted >= remoteVersionCode) {
                    // Bu versiyani avval yuklashga uringanmiz, qayta yuklamaymiz
                    Log.d(TAG, "Update v$remoteVersionCode already attempted, skipping.")
                    return@withContext null
                }

                UpdateInfo(remoteVersionCode, remoteVersionName, downloadUrl, changeLog)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        }
    }

    fun downloadAndInstall(updateInfo: UpdateInfo) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
            if (file.exists()) file.delete()

            val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl)).apply {
                setTitle("JARVIS yangilanmoqda")
                setDescription("v${updateInfo.versionName} yuklanmoqda...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = downloadManager.enqueue(request)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
                    if (id == downloadId) {
                        if (file.exists() && file.length() > 0) {
                            context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
                                .edit()
                                .putInt(KEY_LAST_ATTEMPTED, updateInfo.versionCode)
                                .apply()
                            installApk(file)
                        }
                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            Toast.makeText(context, "✨ Yangilanish yuklab olinmoqda...", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
        }
    }
}
