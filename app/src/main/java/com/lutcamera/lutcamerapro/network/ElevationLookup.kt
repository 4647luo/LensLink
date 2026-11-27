package com.lutcamera.lutcamerapro.network

import android.content.Context
import com.lutcamera.lutcamerapro.log.DoukLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 在线高程查询：给「蓝牙 GPS 推送」补海拔。
 *
 * 为什么需要它：Android 的网络定位（NETWORK_PROVIDER）不带海拔，
 * 平板上又没有气压计（dumpsys sensorservice 里没有 TYPE_PRESSURE），
 * 于是相机收到的 GEO 包里海拔一直是 0，机身上看不到高度。
 * 这里用公开的高程栅格服务按经纬度反查地面高程，结果按位置缓存，
 * 只在「没有本次/GPS/气压计海拔」时兜底，且最多几秒超时，失败就当没有。
 */
object ElevationLookup {

    private const val TIMEOUT_MS = 4_000

    /** 返回该点的地面高程（米，WGS84/EGM 近似），失败返回 null。 */
    fun fetch(context: Context, latitude: Double, longitude: Double): Double? {
        val lat = String.format(Locale.US, "%.5f", latitude)
        val lon = String.format(Locale.US, "%.5f", longitude)

        // 首选 Open-Meteo：无需 key，返回 {"elevation":[43.0]}
        requestJson("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon")
            ?.let { body ->
                val arr = runCatching { JSONObject(body).getJSONArray("elevation") }.getOrNull()
                val value = runCatching { arr?.getDouble(0) }.getOrNull()
                if (value != null && value > -500 && value < 9000) return value
            }

        // 备选 OpenTopoData：返回 {"results":[{"elevation":43.0,...}]}
        requestJson("https://api.opentopodata.org/v1/aster30m?locations=$lat,$lon")
            ?.let { body ->
                val obj = runCatching {
                    JSONObject(body).getJSONArray("results").getJSONObject(0)
                }.getOrNull()
                val value = runCatching { obj?.getDouble("elevation") }.getOrNull()
                if (value != null && value > -500 && value < 9000) return value
            }
        return null
    }

    private fun requestJson(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LensLink/1.1")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.onFailure {
        DoukLog.w("Elevation", "LOOKUP_FAILED", "url" to url, "why" to (it.message ?: ""))
    }.getOrNull()
}
