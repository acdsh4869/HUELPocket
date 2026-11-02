package com.huelpocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.cardBalanceStore: DataStore<Preferences> by preferencesDataStore(name = "card_balance")

/** 上一次成功查到的校园卡余额 */
data class CachedCardBalance(
    /** 余额（元） */
    val yuan: Double,
    /** 查到它的时刻 */
    val at: Long,
    /** 卡片补充信息（姓名/账号/有效期/挂失状态）的 JSON 缓存；旧缓存可能没有 */
    val infoJson: String? = null,
)

/**
 * 校园卡余额的本地缓存。
 *
 * 为什么需要落盘：**余额只能联网查**（一卡通接口），
 * 而一卡通数据表有一条硬规矩 —— 查询失败时展示缓存并标注「数据可能延迟」，
 * 冷启动也先把上次的余额显示出来，不能白屏等接口。
 *
 * 顺带解决另一个小问题：桌面小组件不能联网，App 每次成功查到余额就往这里存一份，
 * 组件只读这份缓存。代价是数字可能不新，组件上用小字如实标注。
 */
class CardBalanceStore(context: Context) {

    private val store = context.applicationContext.cardBalanceStore

    suspend fun latest(): CachedCardBalance? {
        val prefs = store.data.first()
        val yuan = prefs[KEY_YUAN] ?: return null
        val at = prefs[KEY_AT] ?: return null
        return CachedCardBalance(yuan, at, prefs[KEY_INFO])
    }

    suspend fun save(yuan: Double, at: Long = System.currentTimeMillis(), infoJson: String? = null) = store.edit {
        it[KEY_YUAN] = yuan
        it[KEY_AT] = at
        if (infoJson != null) it[KEY_INFO] = infoJson
    }

    suspend fun clear() = store.edit { it.clear() }

    private companion object {
        val KEY_YUAN = doublePreferencesKey("card_balance_yuan")
        val KEY_AT = longPreferencesKey("card_balance_at")
        val KEY_INFO = stringPreferencesKey("card_balance_info")
    }
}
