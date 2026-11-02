package com.huelpocket.app.data.eas

import com.huelpocket.app.core.AppError
import com.huelpocket.app.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 河南财经政法大学正方教务系统课表客户端。
 *
 * 严格对齐《教务接口实测.md》：
 *  - 学期列表：GET /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html（从 HTML 抠 option）
 *  - 课表数据：POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html
 *
 * HUEL 特有：
 *  - 学期编码：3=第1学期、12=第2学期、16=第3学期
 *  - 课表接口路径是 xskbcx_cxXsgrkb.html（不是通用的 xskbcx_cxXsKb.html）
 */
class ZhengFangEasClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val tokenStore: TokenStore,
) {

    /** 每次请求时从 TokenStore 现取：登录/重新登录后自动生效，无需重建客户端 */
    private val sessionId: String get() = tokenStore.zfSessionId.orEmpty()

    // ── DTO ──────────────────────────────────────────────────────────────

    @Serializable
    data class PersonalInfo(
        val xh: String = "",
        val xm: String = "",
        val xnm: String = "",
        val xqm: String = "",
        val xqmc: String = "",
        // 学院 / 专业 / 年级 / 宿舍 / 敏感字段（服务端字段名以实测为准，缺省留空）
        val yxmc: String = "",
        val jgmc: String = "",
        val zyfsmc: String = "",
        val bjmc: String = "",
        val nj: String = "",
        val qsh: String = "",
        val sjhm: String = "",
        val zjhm: String = "",
    )

    @Serializable
    data class SemesterOption(
        val value: String,
        val label: String,
        val selected: Boolean = false,
    )

    @Serializable
    data class SemesterList(
        val years: List<SemesterOption>,
        val terms: List<SemesterOption>,
    )

    @Serializable
    data class CourseItem(
        val kcmc: String = "",           // 课程名
        val kch: String = "",            // 课程号
        val jxbmc: String = "",          // 教学班名
        val jxb_id: String = "",         // 教学班 ID（唯一键）
        val jc: String = "",             // 节次（如 "3-5节"）
        val jcs: String = "",            // 节次（如 "3-5"）
        val xqj: String = "",            // 星期几（1-7）
        val cdmc: String = "",           // 教室
        val cdbh: String = "",           // 教室编号
        val lh: String = "",             // 楼号
        val kcxz: String = "",           // 课程性质
        val kclbmc: String = "",         // 课程类别
        val kcxszc: String = "",         // 学时组成
        val oldzc: String = "",          // 周次位图
        val oldjc: String = "",          // 起止周
        val jxbzc: String = "",          // 班级
    )

    @Serializable
    data class TimetableResponse(
        val xnm: String = "",
        val xqm: String = "",
        val kblx: String = "",
        val xsxx: StudentInfo? = null,
        val xqjmcMap: Map<String, String> = emptyMap(),
        val kbList: List<CourseItem> = emptyList(),
    )

    @Serializable
    data class StudentInfo(
        val XH: String = "",
        val XM: String = "",
        val ZYMC: String = "",
        val XNMC: String = "",
        val KCMS: Int = 0,
        val JSXM: String = "",
    )

    // ── 个人信息 ─────────────────────────────────────────────────────────

    /**
     * 获取个人信息（学籍卡片）
     */
    suspend fun fetchPersonalInfo(): PersonalInfo = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/xsxxxggl/xsxxwh_cxCkDgxsxx.html?gnmkdm=N100801"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "JSESSIONID=$sessionId")
            .header("Referer", "$BASE_URL/xtgl/index_initMenu.html")
            .get()
            .build()

        val body = execute(request)
        try {
            json.decodeFromString<PersonalInfo>(body)
        } catch (e: Exception) {
            throw AppError.Parse("个人信息解析失败", e)
        }
    }

    // ── 学期列表 ─────────────────────────────────────────────────────────

    /**
     * 获取学期列表（从 HTML 页面抠 option）
     */
    suspend fun fetchSemesterList(): SemesterList = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "JSESSIONID=$sessionId")
            .header("Referer", "$BASE_URL/xtgl/index_initMenu.html")
            .get()
            .build()

        val html = execute(request)

        // 抠学年选项
        val years = mutableListOf<SemesterOption>()
        val yearPattern = Regex("""<option value="(\d{4})"[^>]*>([^<]+)</option>""")
        yearPattern.findAll(html).forEach { match ->
            val value = match.groupValues[1]
            val label = match.groupValues[2]
            val selected = match.value.contains("selected")
            years.add(SemesterOption(value, label, selected))
        }

        // 抠学期选项（3=第1学期、12=第2学期、16=第3学期）
        val terms = mutableListOf<SemesterOption>()
        val termPattern = Regex("""<option value="(3|12|16)"[^>]*>([^<]+)</option>""")
        termPattern.findAll(html).forEach { match ->
            val value = match.groupValues[1]
            val label = match.groupValues[2]
            val selected = match.value.contains("selected")
            terms.add(SemesterOption(value, label, selected))
        }

        SemesterList(years = years, terms = terms)
    }

    // ── 课表数据 ─────────────────────────────────────────────────────────

    /**
     * 获取个人课表
     *
     * @param year 学年起始年（如 "2026" 表示 2026-2027 学年）
     * @param term 学期编码（3=第1学期、12=第2学期、16=第3学期）
     */
    suspend fun fetchTimetable(year: String, term: String): TimetableResponse = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
        val formBody = buildString {
            append("xnm=").append(year)
            append("&xqm=").append(term)
            append("&kzlx=ck")
            append("&xsdm=")
            append("&kclbdm=")
            append("&kclxdm=")
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "JSESSIONID=$sessionId")
            .header("Referer", "$BASE_URL/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody.toRequestBody(FORM_MEDIA))
            .build()

        val body = execute(request)
        try {
            json.decodeFromString<TimetableResponse>(body)
        } catch (e: Exception) {
            throw AppError.Parse("课表数据解析失败", e)
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接教务系统，请检查网络", e)
        }

        response.use {
            val text = it.body?.string().orEmpty()
            // 302 / 登录页重渲染都代表会话失效；上层会静默重登，不直接当业务错误展示
            if (!it.isSuccessful) {
                throw AppError.Server(it.code, "HTTP ${it.code}")
            }
            if (it.headers("Location").isNotEmpty() && it.code in 300..399) {
                throw AppError.Server(302, "HTTP 302")
            }
            if (text.contains("id=\"csrftoken\"") && text.contains("login_slogin")) {
                throw AppError.Server(302, "HTTP 302 登录页")
            }

            // 检查是否是错误页（HUEL 的错误页 HTTP 状态也是 200）
            if (text.contains("错误提示") || text.contains("无功能权限")) {
                throw AppError.Server(0, "教务系统返回错误：无功能权限或会话已过期")
            }

            text
        }
    }

    companion object {
        private const val BASE_URL = "https://xk.huel.edu.cn/jwglxt"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }
}
