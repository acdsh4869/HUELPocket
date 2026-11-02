package com.huelpocket.app.ui.nav

/**
 * 导航路由的**唯一来源**。
 *
 * 4 Tab 定型（总览/学习/生活/设置）后 Dock 不再扩展；新功能只往对应 Tab 内部加子页面。
 *
 * ⚠️ 重构前的旧顶层路由（academic / campus_card / library）已彻底删除：
 * 其内容分别迁入「学习」「生活」「设置」tab，二级页一律以 tab 内状态切换实现，
 * 不再注册独立 NavHost 路由 —— 返回时由各二级页显式落回所属 tab，杜绝残留旧页面。
 */
object AppRoutes {

    /** 底部导航的 tab */
    const val SCHEDULE = "schedule"
    const val STUDY = "study"
    const val LIFE = "life"
    const val MINE = "mine"

    /**
     * 小组件带过来的路由 extra。
     *
     * MainActivity 读到它之后会**把它从 Intent 里删掉**：不删的话，转屏、切深色模式
     * 这类 Activity 重建会再次执行跳转，用户正看着明细时会被莫名弹回去。
     */
    const val EXTRA_ROUTE = "com.huelpocket.app.extra.ROUTE"
}
