package com.huelpocket.app.ui.nav

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.huelpocket.app.core.AppGraph
import com.huelpocket.app.data.prefs.UiPrefs
import com.huelpocket.app.data.update.AppUpdate
import com.huelpocket.app.data.update.UpdateCenter
import com.huelpocket.app.ui.about.AboutScreen
import com.huelpocket.app.ui.campus.CampusScreen
import com.huelpocket.app.ui.diag.DiagnosticsScreen
import com.huelpocket.app.ui.focus.FocusScreen
import com.huelpocket.app.ui.focus.FocusStatsScreen
import com.huelpocket.app.ui.login.LoginScreen
import com.huelpocket.app.ui.schedule.BackgroundCropScreen
import com.huelpocket.app.ui.schedule.CourseOverviewScreen
import com.huelpocket.app.ui.schedule.DisplaySettingsScreen
import com.huelpocket.app.ui.schedule.ScheduleTabScreen
import com.huelpocket.app.ui.settings.AppearanceScreen
import com.huelpocket.app.ui.schedule.WallpaperLayers
import com.huelpocket.app.ui.schedule.rememberScheduleBackground
import com.huelpocket.app.ui.web.EmbeddedWebViewScreen
import com.huelpocket.glassui.GlassLevel
import com.huelpocket.glassui.GlassSurface
import com.huelpocket.glassui.GlassTheme



enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    SCHEDULE(AppRoutes.SCHEDULE, "总览", Icons.Filled.CalendarMonth),
    STUDY(AppRoutes.STUDY, "学习", Icons.Filled.MenuBook),
    LIFE(AppRoutes.LIFE, "生活", Icons.Filled.CreditCard),
    MINE(AppRoutes.MINE, "设置", Icons.Filled.Settings),
}

/**
 * tab 切换过渡的时长与曲线。
 *
 * 只在「两端都是 tab」时生效；任一端是二级页（登录/外观/专注/裁剪…）时返回 null，
 * 由 NavHost 用它自己的默认过渡（fadeIn/fadeOut 700ms），与改动前完全一致。
 */
private const val TAB_ANIM_MS = 240

/** 端点是 tab 时返回其在 dock 里的序号，否则 -1 */
private fun tabIndexOf(entry: NavBackStackEntry): Int {
    val route = entry.destination.route
    return Destination.entries.indexOfFirst { it.route == route }
}

/**
 * 横滑切 tab 的公共手势逻辑（松手判定版）。
 *
 * 页面层与 dock 两处调用，仅阈值不同：页面区手势容易划满整屏，用 80dp/800dp·s⁻¹；
 * dock 贴近屏幕底边、实际可滑行程短，用更灵敏的 40dp/500dp·s⁻¹。
 * onDrag 里 change.consume()：dock 手势消费后不再穿透到页面层，避免一次滑动双触发连切两格。
 */
private suspend fun PointerInputScope.tabSwipeGesture(
    currentRoute: String?,
    dragThreshold: Dp,
    flingVelocity: Dp,
    switchTab: (String) -> Unit,
) {
    // 守门：非四个 tab 根页不消费手势，二级页横滑无任何反应
    if (currentRoute == null ||
        Destination.entries.none { it.route == currentRoute }
    ) {
        return
    }

    val dragThresholdPx = dragThreshold.toPx()
    val flingVelocityPxPerSec = flingVelocity.toPx()
    val tracker = VelocityTracker()
    var totalDrag = 0f

    detectHorizontalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            totalDrag = 0f
        },
        onDragEnd = {
            val velocity = tracker.calculateVelocity().x
            // 方向优先取累计位移符号；位移为零再看速度符号
            val direction = when {
                totalDrag > 0f -> 1
                totalDrag < 0f -> -1
                velocity > 0f -> 1
                velocity < 0f -> -1
                else -> 0
            }
            val triggered = abs(totalDrag) > dragThresholdPx ||
                abs(velocity) > flingVelocityPxPerSec
            if (triggered && direction != 0) {
                val index = Destination.entries
                    .indexOfFirst { it.route == currentRoute }
                if (index >= 0) {
                    // 右滑 → 左邻 tab；左滑 → 右邻 tab。
                    // 越界不动、不跳多格、不循环，一次手势只切一格。
                    val target = index + if (direction > 0) -1 else 1
                    if (target in Destination.entries.indices) {
                        switchTab(Destination.entries[target].route)
                    }
                }
            }
            tracker.resetTracking()
            totalDrag = 0f
        },
        onDragCancel = {
            tracker.resetTracking()
            totalDrag = 0f
        },
    ) { change, dragAmount ->
        change.consume()
        tracker.addPosition(change.uptimeMillis, change.position)
        totalDrag += dragAmount
    }
}

/** 目标在右侧 = +1，目标在左侧 = -1；任一端不是 tab 或同一 tab = null */
private fun tabShift(initial: NavBackStackEntry, target: NavBackStackEntry): Int? {
    val from = tabIndexOf(initial)
    val to = tabIndexOf(target)
    if (from < 0 || to < 0 || from == to) return null
    return if (to > from) 1 else -1
}

/** 总览课表网格本身有横向滚动，只在它参与的 tab 切换中使用横滑。 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.useHorizontalTabSlide(): Boolean =
    initialState.destination.route == Destination.SCHEDULE.route ||
        targetState.destination.route == Destination.SCHEDULE.route

/** 新页入场：总览按 dock 方向横滑，其他 tab 从下方滑入，避免与课表网格手势冲突。 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnter(): EnterTransition? {
    val shift = tabShift(initialState, targetState) ?: return null
    return if (useHorizontalTabSlide()) {
        slideInHorizontally(
            animationSpec = tween(TAB_ANIM_MS, easing = FastOutSlowInEasing),
            initialOffsetX = { width -> if (shift > 0) width / 4 else -width / 4 },
        ) + fadeIn(tween(TAB_ANIM_MS, easing = FastOutSlowInEasing))
    } else {
        slideInVertically(
            animationSpec = tween(TAB_ANIM_MS, easing = FastOutSlowInEasing),
            initialOffsetY = { height -> height / 4 },
        ) + fadeIn(tween(TAB_ANIM_MS, easing = FastOutSlowInEasing))
    }
}

/** 旧页退场：总览朝反方向横移，其他 tab 向上滑出。 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExit(): ExitTransition? {
    val shift = tabShift(initialState, targetState) ?: return null
    return if (useHorizontalTabSlide()) {
        slideOutHorizontally(
            animationSpec = tween(TAB_ANIM_MS, easing = FastOutSlowInEasing),
            targetOffsetX = { width -> if (shift > 0) -width / 10 else width / 10 },
        ) + fadeOut(tween(TAB_ANIM_MS, easing = FastOutSlowInEasing))
    } else {
        slideOutVertically(
            animationSpec = tween(TAB_ANIM_MS, easing = FastOutSlowInEasing),
            targetOffsetY = { height -> -height / 10 },
        ) + fadeOut(tween(TAB_ANIM_MS, easing = FastOutSlowInEasing))
    }
}

/** 非 tab 的全屏页面：这些路由下不显示底部导航栏 */
private const val ROUTE_LOGIN = "login"
private const val ROUTE_DIAGNOSTICS = "diagnostics"
private const val ROUTE_OVERVIEW = "course_overview"
private const val ROUTE_DISPLAY = "display_settings"
private const val ROUTE_FOCUS_STATS = "focus_stats"
private const val ROUTE_BG_CROP = "background_crop"
private const val ROUTE_ABOUT = "about"
private const val ROUTE_APPEARANCE = "appearance"
private const val ROUTE_FOCUS = "focus"

/** "重新裁剪"时用这个占位值代替相册 URI */
private const val CROP_SOURCE_SAVED = "saved"

@Composable
fun AppNav(initialRoute: String? = null, routeToken: Int = 0) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Destination.entries.any { it.route == currentRoute }

    /**
     * 切到某个 tab。所有“进 tab”的路径都走这里，避免 tab 状态被压栈污染。
     */
    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(initialRoute, routeToken) {
        if (initialRoute == null || initialRoute == Destination.SCHEDULE.route) return@LaunchedEffect
        if (Destination.entries.any { it.route == initialRoute }) {
            switchTab(initialRoute)
        } else {
            navController.navigate(initialRoute)
        }
    }

    // 返回优先级（体检 #12）：非总览 tab 根页面按返回 → 先回总览 tab（原实现空操作
    // 「停留当前 tab」，返回键像坏了）；在总览根页面再返回才交给系统退出 App。
    // 二级页由各自 BackHandler 处理，不受影响。
    if (Destination.entries.any { it.route == currentRoute } &&
        currentRoute != Destination.SCHEDULE.route
    ) {
        androidx.activity.compose.BackHandler(enabled = true) {
            switchTab(Destination.SCHEDULE.route)
        }
    }

    val context = LocalContext.current
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())

    // 壁纸只认「课表」这一页；切到别的 tab 就不画
    val wallpaperActive = currentRoute == Destination.SCHEDULE.route && prefs.backgroundPath != null
    val wallpaper = rememberScheduleBackground(
        context = context,
        path = prefs.backgroundPath.takeIf { wallpaperActive },
        version = prefs.backgroundVersion,
    )

    // 更新红点只挂在「我的」图标上
    val currentVersion = remember { AppUpdate.localVersion(context) }
    val remoteRelease by UpdateCenter.release.collectAsState(initial = null)
    val mutedVersion by UpdateCenter.mutedVersion.collectAsState(initial = null)
    val hasUpdate = AppUpdate.shouldShowDot(currentVersion, remoteRelease?.version, mutedVersion)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (wallpaperActive && wallpaper != null) {
            WallpaperLayers(image = wallpaper, prefs = prefs)
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // 横滑切 tab（松手判定版）：只在四个 tab 根页注册；二级页横滑无任何反应。
                    // pointerInput 以 currentRoute 为 key —— 离开 tab 根页时本块整体重建并直接返回。
                    // 总览周视图网格自带 horizontalScroll：在网格上起手的横滑由子级手势优先消费，不切 tab。
                    .pointerInput(currentRoute) {
                        tabSwipeGesture(
                            currentRoute = currentRoute,
                            dragThreshold = 80.dp,
                            flingVelocity = 800.dp,
                            switchTab = ::switchTab,
                        )
                    },
            ) {
                // ⚠️ 嵌入式 Tab 页面没有自己的 Surface，LocalContentColor 会保持默认黑色，
                // 暗色模式下所有未显式指定颜色的文字/图标都不可见（第七轮实测缺陷）。
                // 这里统一注入 onSurface 语义色；背景保持透明不破坏课表壁纸。
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    NavHost(
                    navController = navController,
                    startDestination = Destination.SCHEDULE.route,
                ) {
                    composable(
                        route = Destination.SCHEDULE.route,
                        // tab↔tab：按 dock 顺序做方向滑动；与二级页之间返回 null，沿用 NavHost 默认淡入淡出
                        enterTransition = { tabEnter() },
                        exitTransition = { tabExit() },
                        popEnterTransition = { tabEnter() },
                        popExitTransition = { tabExit() },
                    ) {
                        ScheduleTabScreen(
                            onOpenLogin = { navController.navigate(ROUTE_LOGIN) },
                            onOpenOverview = { semesterId ->
                                navController.navigate("$ROUTE_OVERVIEW/$semesterId")
                            },
                            onOpenDisplaySettings = { navController.navigate(ROUTE_DISPLAY) },
                            onOpenFocus = { navController.navigate(ROUTE_FOCUS) },
                            onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                        )
                    }
                    composable(
                        route = Destination.STUDY.route,
                        // tab↔tab：按 dock 顺序做方向滑动；与二级页之间返回 null，沿用 NavHost 默认淡入淡出
                        enterTransition = { tabEnter() },
                        exitTransition = { tabExit() },
                        popEnterTransition = { tabEnter() },
                        popExitTransition = { tabExit() },
                    ) {
                        com.huelpocket.app.ui.study.StudyScreen()
                    }
                    composable(
                        route = Destination.LIFE.route,
                        // tab↔tab：按 dock 顺序做方向滑动；与二级页之间返回 null，沿用 NavHost 默认淡入淡出
                        enterTransition = { tabEnter() },
                        exitTransition = { tabExit() },
                        popEnterTransition = { tabEnter() },
                        popExitTransition = { tabExit() },
                    ) {
                        com.huelpocket.app.ui.life.LifeScreen()
                    }
                    composable(
                        route = Destination.MINE.route,
                        // tab↔tab：按 dock 顺序做方向滑动；与二级页之间返回 null，沿用 NavHost 默认淡入淡出
                        enterTransition = { tabEnter() },
                        exitTransition = { tabExit() },
                        popEnterTransition = { tabEnter() },
                        popExitTransition = { tabExit() },
                    ) {
                        CampusScreen(
                            onOpenLogin = { navController.navigate(ROUTE_LOGIN) },
                            onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                            onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
                            onOpenAppearance = { navController.navigate(ROUTE_APPEARANCE) },
                            onOpenDisplaySettings = { navController.navigate(ROUTE_DISPLAY) },
                        )
                    }
                    composable(ROUTE_FOCUS) {
                        FocusScreen(onOpenStats = { navController.navigate(ROUTE_FOCUS_STATS) })
                    }
                    composable(ROUTE_APPEARANCE) {
                        AppearanceScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = "$ROUTE_OVERVIEW/{semesterId}",
                        arguments = listOf(navArgument("semesterId") { type = NavType.IntType }),
                    ) { entry ->
                        val semesterId = entry.arguments?.getInt("semesterId") ?: return@composable
                        CourseOverviewScreen(
                            semesterId = semesterId,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_DISPLAY) {
                        DisplaySettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenOverview = { semesterId ->
                                navController.navigate("$ROUTE_OVERVIEW/$semesterId")
                            },
                        )
                    }
                    composable(ROUTE_FOCUS_STATS) {
                        FocusStatsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_LOGIN) {
                        LoginScreen(
                            onDone = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_DIAGNOSTICS) {
                        // 发版卫生：诊断页（登录态/菜单侦察）只在 debug 包可达，release 不可入
                        if (com.huelpocket.app.BuildConfig.DEBUG) {
                            DiagnosticsScreen(onBack = { navController.popBackStack() })
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("该页面仅在内测版本中开放")
                            }
                        }
                    }
                    composable(
                        route = "$ROUTE_BG_CROP/{source}",
                        arguments = listOf(navArgument("source") { type = NavType.StringType }),
                    ) { entry ->
                        val raw = entry.arguments?.getString("source") ?: return@composable
                        BackgroundCropScreen(
                            sourceUri = if (raw == CROP_SOURCE_SAVED) null else Uri.parse(Uri.decode(raw)),
                            useSavedSource = raw == CROP_SOURCE_SAVED,
                            onSaved = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                if (showBottomBar) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        GlassSurface(
                            shape = GlassTheme.shapes.capsule,
                            level = GlassLevel.Chrome,
                            contentColor = GlassTheme.colors.textPrimary,
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .fillMaxWidth()
                                .height(56.dp)
                                // dock 独立横滑手势：贴屏幕底边实际行程短，阈值比页面层灵敏
                                //（40dp/500dp·s⁻¹ vs 页面 80dp/800dp·s⁻¹）。
                                // Tap 与 drag 由 touch slop 区分，图标点击不受影响。
                                .pointerInput(currentRoute) {
                                    tabSwipeGesture(
                                        currentRoute = currentRoute,
                                        dragThreshold = 40.dp,
                                        flingVelocity = 500.dp,
                                        switchTab = ::switchTab,
                                    )
                                },
                        ) {
                            // 选中态只保留一个胶囊：按选中项整颗滑动（各 item 内不再各自画背景）
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val slotWidth = maxWidth / Destination.entries.size
                                val selectedIndex = Destination.entries
                                    .indexOfFirst { it.route == currentRoute }
                                    .coerceAtLeast(0)
                                val capsuleOffset by animateDpAsState(
                                    targetValue = slotWidth * selectedIndex + (slotWidth - 40.dp) / 2,
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                    label = "dockCapsule",
                                )
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        .offset(x = capsuleOffset)
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(GlassTheme.colors.selection)
                                        .border(2.dp, GlassTheme.colors.selectionStroke, CircleShape),
                                )
                                Row(
                                    Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    Destination.entries.forEach { destination ->
                                        val selected = currentRoute == destination.route
                                        val tint by animateColorAsState(
                                            targetValue = if (selected) {
                                                GlassTheme.colors.accent
                                            } else {
                                                GlassTheme.colors.textSecondary
                                            },
                                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                                            label = "dockIconTint",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clickable { switchTab(destination.route) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            // 40dp 盒子保留：红点仍按它定位、仍被圆裁剪，与改动前完全一致
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    destination.icon,
                                                    contentDescription = destination.label,
                                                    tint = tint,
                                                    modifier = Modifier.size(23.dp),
                                                )
                                                if (destination == Destination.MINE && hasUpdate) {
                                                    Box(
                                                        Modifier
                                                            .align(Alignment.TopEnd)
                                                            .offset(x = 1.dp, y = (-1).dp)
                                                            .size(9.dp)
                                                            .clip(CircleShape)
                                                            .background(GlassTheme.colors.danger),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                } // CompositionLocalProvider（onSurface 语义色，覆盖 NavHost 与 Dock）
            }
        }
    }
}

/**
 * 悬浮 dock 为「滚动内容」预留下的底部余量：胶囊 56 + 上下边距 16 = 72，再加 16 呼吸。
 */
val DOCK_CLEARANCE = 88.dp
