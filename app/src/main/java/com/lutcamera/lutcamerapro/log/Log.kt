package com.lutcamera.lutcamerapro.log

import timber.log.Timber

/**
 * `android.util.Log` 的等价替身，内部统一走 Timber。
 *
 * 背景：工程里已经 plant 了 Timber Tree（文件和 xLog 都挂在它下面），但历史代码
 * 仍然 `import android.util.Log`，两套日志并存 —— 直接 Log 打的内容不会进文件日志，
 * 线上排障时看不到。Lint 的 LogNotTimber 也是因为这个。
 *
 * 这里保持与 `android.util.Log` 相同的调用形状（tag + msg，或 + throwable），
 * 各文件只需要把 import 换成本对象，调用一行都不用改，日志统一落到 Timber。
 */
object Log {

    fun v(tag: String, msg: String) = Timber.tag(tag).v(msg)
    fun d(tag: String, msg: String) = Timber.tag(tag).d(msg)
    fun i(tag: String, msg: String) = Timber.tag(tag).i(msg)
    fun w(tag: String, msg: String) = Timber.tag(tag).w(msg)
    fun e(tag: String, msg: String) = Timber.tag(tag).e(msg)
    fun wtf(tag: String, msg: String) = Timber.tag(tag).wtf(msg)

    fun v(tag: String, msg: String, tr: Throwable?) = Timber.tag(tag).v(tr, msg)
    fun d(tag: String, msg: String, tr: Throwable?) = Timber.tag(tag).d(tr, msg)
    fun i(tag: String, msg: String, tr: Throwable?) = Timber.tag(tag).i(tr, msg)
    fun w(tag: String, msg: String, tr: Throwable?) = Timber.tag(tag).w(tr, msg)
    fun e(tag: String, msg: String, tr: Throwable?) = Timber.tag(tag).e(tr, msg)
    fun wtf(tag: String, msg: String, tr: Throwable?) = Timber.tag(tag).wtf(tr, msg)

    fun v(tag: String, msg: String, vararg args: Any?) = Timber.tag(tag).v(msg, *args)
    fun d(tag: String, msg: String, vararg args: Any?) = Timber.tag(tag).d(msg, *args)
    fun i(tag: String, msg: String, vararg args: Any?) = Timber.tag(tag).i(msg, *args)
    fun w(tag: String, msg: String, vararg args: Any?) = Timber.tag(tag).w(msg, *args)
    fun e(tag: String, msg: String, vararg args: Any?) = Timber.tag(tag).e(msg, *args)

    /** 与 `Log.getStackTraceString` 对齐，异常路径里偶尔会用到。 */
    fun getStackTraceString(tr: Throwable?): String =
        android.util.Log.getStackTraceString(tr)

    /** 与 `Log.isLoggable` 对齐。 */
    fun isLoggable(tag: String, level: Int): Boolean = android.util.Log.isLoggable(tag, level)
}
