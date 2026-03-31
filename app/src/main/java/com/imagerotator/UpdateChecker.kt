package com.imagerotator

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OTA 업데이트 체커
 *
 * update.json 형식:
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "apkUrl": "https://your-server.com/ImageRotator-release.apk",
 *   "changelog": "회전 속도 개선, 버그 수정"
 * }
 */
object UpdateChecker {

    // ★ 여기에 update.json URL을 설정하세요
    private const val UPDATE_URL = "https://raw.githubusercontent.com/4ndrxxs/ImageRotator/main/update.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(UPDATE_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val serverVersionCode = json.getInt("versionCode")
                if (serverVersionCode <= currentVersionCode) return@withContext null

                UpdateInfo(
                    versionCode = serverVersionCode,
                    versionName = json.getString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    changelog = json.optString("changelog", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun downloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        val fileName = "ImageRotator-${updateInfo.versionName}.apk"

        // 기존 파일 삭제
        val destFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (destFile.exists()) destFile.delete()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(updateInfo.apkUrl))
            .setTitle("이미지 회전기 업데이트")
            .setDescription("v${updateInfo.versionName} 다운로드 중...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = downloadManager.enqueue(request)

        // 다운로드 완료 시 설치
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    installApk(ctx, destFile)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
