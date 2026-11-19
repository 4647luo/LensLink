package com.lutcamera.lutcamerapro.log

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 全局 HTTP 客户端：地图瓦片等轻量请求共用，所有请求都过网络日志拦截器。 */
object DoukHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(6000, TimeUnit.MILLISECONDS)
            .addInterceptor(NetworkLoggingInterceptor())
            .build()
    }
}
