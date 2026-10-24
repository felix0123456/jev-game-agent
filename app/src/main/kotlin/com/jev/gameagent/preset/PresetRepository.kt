package com.jev.gameagent.preset

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class PresetRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("jev_presets", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_PRESETS_JSON = "presets_json"
        private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
    }

    fun getPresets(): List<GoalPreset> {
        val json = prefs.getString(KEY_PRESETS_JSON, null)
        if (json.isNullOrBlank()) {
            val defaults = GoalPreset.createStarterPresets()
            savePresets(defaults)
            return defaults
        }
        return try {
            val type = object : TypeToken<List<GoalPreset>>() {}.type
            val list: List<GoalPreset>? = gson.fromJson(json, type)
            if (list.isNullOrEmpty()) {
                val defaults = GoalPreset.createStarterPresets()
                savePresets(defaults)
                defaults
            } else {
                list
            }
        } catch (e: Exception) {
            val defaults = GoalPreset.createStarterPresets()
            savePresets(defaults)
            defaults
        }
    }

    fun savePresets(presets: List<GoalPreset>) {
        val json = gson.toJson(presets)
        prefs.edit().putString(KEY_PRESETS_JSON, json).apply()
    }

    fun savePreset(preset: GoalPreset) {
        val current = getPresets().toMutableList()
        val index = current.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            current[index] = preset
        } else {
            current.add(preset)
        }
        savePresets(current)
    }

    fun deletePreset(presetId: String) {
        val current = getPresets().filter { it.id != presetId }
        savePresets(current)
    }

    fun getActivePreset(): GoalPreset? {
        val activeId = prefs.getString(KEY_ACTIVE_PRESET_ID, null)
        val all = getPresets()
        return all.find { it.id == activeId } ?: all.firstOrNull()
    }

    fun setActivePresetId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_ID, id).apply()
    }

    fun exportPresetToJson(preset: GoalPreset): String {
        return gson.toJson(preset)
    }

    fun exportAllToJson(): String {
        return gson.toJson(getPresets())
    }

    fun importFromJson(jsonStr: String): Boolean {
        return try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                val type = object : TypeToken<List<GoalPreset>>() {}.type
                val importedList: List<GoalPreset> = gson.fromJson(trimmed, type)
                if (importedList.isNotEmpty()) {
                    val current = getPresets().toMutableList()
                    for (item in importedList) {
                        val withNewId = item.copy(id = UUID.randomUUID().toString())
                        current.add(0, withNewId)
                    }
                    savePresets(current)
                    return true
                }
            } else if (trimmed.startsWith("{")) {
                val single: GoalPreset = gson.fromJson(trimmed, GoalPreset::class.java)
                val withNewId = single.copy(id = UUID.randomUUID().toString())
                savePreset(withNewId)
                setActivePresetId(withNewId.id)
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
