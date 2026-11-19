package com.lutcamera.lutcamerapro.log

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * 网络日志拦截器：只记录方法、主机、路径、状态码、耗时。
 * 不记录 Authorization / Cookie / 查询参数，避免把账号或定位 token 写进用户发出来的日志。
 */
class NetworkLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val safeUrl = buildString {
            append(request.url.scheme).append("://").append(request.url.host)
            if (request.url.port != 80 && request.url.port != 443) append(':').append(request.url.port)
            append(request.url.encodedPath)
        }
        val started = System.nanoTime()
        Timber.d("HTTP → %s %s", request.method, safeUrl)
        return try {
            val response = chain.proceed(request)
            val costMs = (System.nanoTime() - started) / 1_000_000
            Timber.d(
                "HTTP ← %d %s (%dms, %s)",
                response.code,
                safeUrl,
                costMs,
                response.header("Content-Length") ?: "chunked"
            )
            response
        } catch (e: IOException) {
            val costMs = (System.nanoTime() - started) / 1_000_000
            Timber.w(e, "HTTP ✕ %s (%dms)", safeUrl, costMs)
            throw e
        }
    }
}
