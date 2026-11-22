package com.lutcamera.lutcamerapro.ui.douk

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查走 GitHub Releases。
 *
 * 发布新版本时只要在 GitHub 建一个 v0.0.2 之类的最新 Release，
 * 并把 APK 作为附件上传，App 里就能读到版本号、更新说明和下载地址。
 */
object GitHubUpdateChecker {
    const val OWNER = "4647luo"
    const val REPO = "lianjing"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO"

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?,
        val apkSize: Long,
        val publishedAt: String,
        val isNewer: Boolean,
    )

    sealed interface Result {
        data class Success(val info: ReleaseInfo) : Result
        data class NoRelease(val repoUrl: String) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        try {
            val body = request("$API/releases/latest")
            if (body == null) {
                return@withContext Result.NoRelease("https://github.com/$OWNER/$REPO/releases")
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            val name = json.optString("name").ifBlank { tag }
            val notes = json.optString("body").trim()
            val pageUrl = json.optString("html_url").ifBlank { "https://github.com/$OWNER/$REPO/releases" }
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name").lowercase().endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        apkSize = asset.optLong("size")
                        break
                    }
                }
            }
            Result.Success(
                ReleaseInfo(
                    tag = tag,
                    name = name,
                    notes = notes,
                    pageUrl = pageUrl,
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    publishedAt = json.optString("published_at").take(10),
                    isNewer = isVersionNewer(tag, currentVersion),
                )
            )
        } catch (e: Exception) {
            Result.Failure(e.message ?: "网络请求失败")
        }
    }

    private fun request(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "LianJing-Android/${Build.VERSION.SDK_INT}")
            when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> error("GitHub 返回 ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 0.0.1 / v0.0.1 / 0.0.1-beta 都按数字段比较，避免字符串排序误判。 */
    fun isVersionNewer(remote: String, current: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(current)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun versionParts(value: String): List<Int> {
        val cleaned = value.trim().removePrefix("v").removePrefix("V")
        return Regex("\\d+").findAll(cleaned).mapNotNull { it.value.toIntOrNull() }.toList()
    }
}
