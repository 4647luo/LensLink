package com.lutcamera.lutcamerapro.model

import android.content.Context
import com.google.gson.Gson

object BorderStateRepository {

    private const val PREF_NAME = "lut_camera_border_prefs"
    private const val KEY_BORDER_STATE = "key_last_border_state"
    private val gson = Gson()

    /**
     * 💾 保存边框参数到本地     */
    fun saveBorderState(context: Context, state: BorderState) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(state)
            prefs.edit().putString(KEY_BORDER_STATE, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 📖 读取上次退出时保存的边框参数     */
    fun loadBorderState(context: Context): BorderState {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_BORDER_STATE, null)
            if (!json.isNullOrBlank()) {
                gson.fromJson(json, BorderState::class.java) ?: BorderState()
            } else {
                BorderState()
            }
        } catch (e: Exception) {
            BorderState()
        }
    }
}
