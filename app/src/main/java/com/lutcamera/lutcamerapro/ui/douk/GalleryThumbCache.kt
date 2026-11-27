package com.lutcamera.lutcamerapro.ui.douk

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 图库缩略图缓存。
 *
 * 之前的实现用 `mutableStateMapOf`：每写入一张，所有读这张 map 的格子都会重组，
 * 快速滚动时等于「每加载一张图刷新一次整屏」。这里改成每个 key 一份独立的
 * MutableState，格子只观察自己那一格，别的格子加载完不会牵动它。
 *
 * 淘汰按真正的 LRU：LinkedHashMap 用 accessOrder=true，读取（stateOf/peek）
 * 会把该 key 移到队尾，超容量时踢掉队首——也就是最久没被看到的缩略图，
 * 而不是最早插入的那张。容量按堆大小算，低内存机型自动收窄。
 */
internal object GalleryThumbCache {

    /** 网格缩略图长边，和 GalleryScreen 的 GALLERY_THUMB_SIDE 保持一致。 */
    const val THUMB_SIDE = 420

    private val capacity: Int = run {
        val heap = Runtime.getRuntime().maxMemory()
        val perThumb = THUMB_SIDE.toLong() * THUMB_SIDE * 4L
        // 最多用堆的 1/8；24 张起步（低端机也不会太吃内存），96 张封顶。
        ((heap / 8) / perThumb).toInt().coerceIn(24, 96)
    }

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, MutableState<ImageBitmap?>>(32, 0.75f, true) {}

    /** 取某一格的缩略图状态。读它就算一次访问，会刷新 LRU 顺序。 */
    fun stateOf(key: String): State<ImageBitmap?> = synchronized(lock) {
        entries.getOrPut(key) { mutableStateOf(null) }
    }

    /** 只读当前值，也计入一次访问。 */
    fun peek(key: String): ImageBitmap? = synchronized(lock) { entries[key]?.value }

    fun contains(key: String): Boolean = synchronized(lock) { entries.containsKey(key) }

    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(lock) {
            val state = entries.getOrPut(key) { mutableStateOf(null) }
            state.value = bitmap
            while (entries.size > capacity) {
                val eldest = entries.entries.firstOrNull { it.key != key } ?: break
                // 先把状态置空，正在显示的格子会回到占位，再移除条目。
                eldest.value.value = null
                entries.remove(eldest.key)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.values.forEach { it.value = null }
            entries.clear()
        }
    }

    val size: Int get() = synchronized(lock) { entries.size }
}
