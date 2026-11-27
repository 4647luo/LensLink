package com.lutcamera.lutcamerapro.util

import com.lutcamera.lutcamerapro.ui.douk.DoukPrefs
import org.json.JSONArray
import org.json.JSONObject

/** 一套版权信息，可以在设置里存成模板反复套用。 */
data class IptcTemplate(
    val name: String,
    val artist: String = "",
    val copyright: String = "",
    val credit: String = "",
    val keywords: String = ""
) {
    fun toStampProfile(): IptcStamp.Profile = IptcStamp.Profile(
        artist = artist,
        copyright = copyright,
        credit = credit,
        keywords = keywords
            .split(',', '，', ';', '；', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    )
}

/**
 * 模板列表存在 DoukPrefs 的一个 JSON 字符串里；
 * 当前「会写进照片」的那套值单独存在 iptc_artist / iptc_copyright 等字段，
 * 这样换模板只是把字段覆盖一遍，写入链路不必再关心模板是谁。
 */
object IptcTemplates {

    private const val KEY = "iptc_templates_json"

    fun list(): List<IptcTemplate> {
        val raw = DoukPrefs.raw(KEY)
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                IptcTemplate(
                    name = obj.optString("name", "未命名"),
                    artist = obj.optString("artist", ""),
                    copyright = obj.optString("copyright", ""),
                    credit = obj.optString("credit", ""),
                    keywords = obj.optString("keywords", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAll(items: List<IptcTemplate>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("artist", item.artist)
                    put("copyright", item.copyright)
                    put("credit", item.credit)
                    put("keywords", item.keywords)
                }
            )
        }
        DoukPrefs.putRaw(KEY, array.toString())
    }

    /** 同名覆盖，不同名追加。 */
    fun upsert(item: IptcTemplate) {
        val all = list()
        val index = all.indexOfFirst { it.name.equals(item.name, ignoreCase = true) }
        saveAll(if (index >= 0) all.toMutableList().apply { set(index, item) } else all + item)
    }

    fun delete(name: String) {
        saveAll(list().filterNot { it.name.equals(name, ignoreCase = true) })
    }

    /** 下载时真正生效的那套值。 */
    fun active(): IptcTemplate = IptcTemplate(
        name = DoukPrefs.iptcTemplateName,
        artist = DoukPrefs.iptcArtist,
        copyright = DoukPrefs.iptcCopyright,
        credit = DoukPrefs.iptcCredit,
        keywords = DoukPrefs.iptcKeywords
    )

    /** 把某套模板设为当前生效值。 */
    fun activate(item: IptcTemplate) {
        DoukPrefs.iptcTemplateName = item.name
        DoukPrefs.iptcArtist = item.artist
        DoukPrefs.iptcCopyright = item.copyright
        DoukPrefs.iptcCredit = item.credit
        DoukPrefs.iptcKeywords = item.keywords
    }
}
