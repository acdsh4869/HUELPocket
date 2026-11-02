package com.huelpocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.huelpocket.app.core.HuelTimetable
import com.huelpocket.app.domain.Period
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.periodSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "period_settings")

@Serializable
private data class StoredPeriod(
    val indexNo: Int,
    val nameZh: String,
    val startMinute: Int,
    val endMinute: Int,
    val dayPart: String,
    val segmentIndex: Int,
)

/**
 * 节次作息的唯一活跃数据源。
 *
 * 课表周视图、今天页、小组件和设置页全部从这里读取；
 * `HuelTimetable` 只负责提供“恢复默认”的初始值，不再作为第二套运行时来源。
 */
class PeriodSettingsStore(context: Context) {

    private val store = context.applicationContext.periodSettingsStore
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val periodsFlow: Flow<List<Period>> = store.data.map { prefs ->
        decode(prefs[KEY_PERIODS])
    }

    suspend fun periods(): List<Period> = periodsFlow.first()

    suspend fun savePeriods(items: List<Period>) {
        val sorted = items.sortedBy { it.indexNo }
        store.edit { it[KEY_PERIODS] = json.encodeToString(sorted.map { it.toStored() }) }
    }

    suspend fun restoreDefault() {
        store.edit { it.remove(KEY_PERIODS) }
    }

    private fun decode(raw: String?): List<Period> {
        if (raw.isNullOrBlank()) return HuelTimetable.PERIODS.map { it.toDomain() }
        return runCatching {
            json.decodeFromString<List<StoredPeriod>>(raw).map { it.toDomain() }
        }.getOrElse { HuelTimetable.PERIODS.map { it.toDomain() } }
    }

    private fun HuelTimetable.Period.toDomain() = Period(
        indexNo = indexNo,
        nameZh = nameZh,
        startTime = startMinute,
        endTime = endMinute,
        dayPart = dayPart,
        segmentIndex = segmentIndex,
    )

    private fun Period.toStored() = StoredPeriod(
        indexNo = indexNo,
        nameZh = nameZh,
        startMinute = startTime,
        endMinute = endTime,
        dayPart = dayPart,
        segmentIndex = segmentIndex,
    )

    private fun StoredPeriod.toDomain() = Period(
        indexNo = indexNo,
        nameZh = nameZh,
        startTime = startMinute,
        endTime = endMinute,
        dayPart = dayPart,
        segmentIndex = segmentIndex,
    )

    private companion object {
        val KEY_PERIODS = stringPreferencesKey("periods_json_v1")
    }
}
