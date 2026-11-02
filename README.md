# HUELPocket

HUEL 校园助手（Android，非官方）

[![Release](https://img.shields.io/github/v/release/acdsh4869/HUELPocket?style=flat&label=release)](../../releases)
[![License](https://img.shields.io/github/license/acdsh4869/HUELPocket?style=flat)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B-3ddc84?style=flat&logo=android&logoColor=white)](#下载安装)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff?style=flat&logo=kotlin&logoColor=white)](#技术栈)

> ⚠️ 本项目是非官方项目，与学校无任何隶属关系。
> 接口来自公开网页的协议整理，随时可能失效或被调整。
> 本项目**参考、借鉴了 [3beuml/ZZUPocket](https://github.com/3beuml/ZZUPocket)（MIT）的部分代码与实现思路**，在此基础上按本校接口与功能需求重写，并非其分支。

## 功能特性

**总览**

- **课表**：日 / 周两种视图，教学周切换，周次表头带日期、今天高亮；
  教务课与自定义日程混排；课程总览按周浏览；
  自定义课表背景图（裁剪 + 模糊与浓淡调节）
- **今天**：一条时间轴看"今天有什么"——日程 + 专注记录，未登录也有干净的空态
- **专注（番茄钟）**：前台服务计时，专注历史与统计（周 / 月视角）
- **课程表导出**：一键把整学期课表导出为 `.ics`，导入系统日历，
  提醒交给系统日历（跨品牌可靠，无需 App 保活）

**学习**

- **教务系统**：内嵌统一身份认证后的教务入口，成绩 / 考试 / 空教室 / 培养方案等全部功能
- **图书馆**：扫码入馆 / 预约座位

**生活**

- **一卡通**：余额只读展示（充值缴费走官方入口，App 不做任何资金操作）
- **胖乖生活**：登录后扫码使用共享洗衣机、查看我的洗衣机与订单

**桌面小组件**（RemoteViews + XML，只读本地数据）

- **下一节课**：一节课大字 + 时间段 + 地点
- **今明课表**：左列今天、右列明天，各列出还没上的课

**系统**

- **应用内更新**：自动检查 GitHub Releases，发现新版本给红点与更新弹窗，可"不再提醒"
- **主题与深色模式**：灰玻璃质感默认，深浅色自适应，另有可选配色（支持 Material You 动态取色）
- **节次时间与开学日期**：13 节作息可改，教学周起点可确认
- **手势与导航**：底部 dock 选中胶囊 + 页面横滑切 tab；非总览页按返回键先回总览

## 下载安装

到 [Releases](../../releases/latest) 下载最新版的 `app-release.apk`，在手机上安装即可
（Android 12 / API 31 及以上）。

- 装在手机上会走系统的"未知来源应用"授权。
- 后续升级可以直接在 App 内「设置 → 检测更新」，或覆盖安装新包，**数据不丢**。

## 首次使用

1. **登录**：设置 → 账号，走学校统一身份认证。**App 不保存密码**，只保存加密后的登录令牌。
2. **设置开学日期**：设置 → 节次时间与开学日期，确认第 1 周的周一，课表周次才会对齐。
   （教务返回开学日期时会自动带出，核对即可。）
3. **提醒建议走日历**：课程提醒不依赖本 App 后台——
   在设置里把课表导出为 `.ics` 导入系统日历，由日历 App 负责提醒，
   各品牌的预装日历都在自家后台白名单里，比第三方闹钟可靠。

## 项目结构

```
app/src/main/java/com/huelpocket/app/
├── core/            纯逻辑与基础设施：手动依赖容器 AppGraph、加密、HTTP 客户端、错误文案
├── data/
│   ├── auth/        统一身份认证登录
│   ├── eas/         教务系统（课表、节次）
│   ├── ecard/       一卡通（余额、流水）、胖乖生活（洗衣机）
│   ├── db/          Room 实体与 DAO
│   ├── prefs/       DataStore（界面偏好）+ 加密令牌
│   ├── repo/        Repository：把接口与本地库缝在一起
│   └── update/      应用内更新（GitHub Releases）
├── domain/          纯数据模型（课表、周次对齐、专注统计）
├── focus/           番茄钟引擎、通知、前台服务
└── ui/              Compose 界面：today / schedule / focus / study / life / card /
                     campus / widget（桌面小组件）/ login / about / settings / nav

glass-ui/            玻璃质感设计系统：磨砂卡片、dock、配色 token
```

## 技术栈

| 方面 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0 + Jetpack Compose + Material 3（Material You 动态取色 + 自研 glass-ui 质感层） |
| 依赖注入 | **手写**（`core/AppGraph.kt`）—— 依赖不多，DI 框架收益为负 |
| 网络 | OkHttp + kotlinx.serialization（**不用 Retrofit**：接口少，裸 OkHttp 更直接） |
| 本地存储 | Room（课表 / 专注）、DataStore（偏好）、EncryptedSharedPreferences + Tink（令牌） |
| 桌面小组件 | AppWidgetProvider + RemoteViews + XML（不用 Glance） |
| 解析 | CameraX + ML Kit（bundled，无 GMS 依赖） |
| 后端 | **没有**。不经过任何自建中转服务器，账号凭据与数据只在设备和学校系统之间流动 |

## 隐私与边界

- **不保存密码**。登录只走学校统一身份认证，本机仅保存加密后的登录令牌；退出登录会清除。
- **数据只在本机**：课表、专注记录都存在本机数据库，**没有自建服务器，也不会上传**。
  卸载 App 会一并清掉。
- **只读接口**：不做校园卡充值转账、不做自动抢座；一卡通只读展示余额。
- **不采集**：没有统计 SDK、没有崩溃上报、没有广告。
- 仓库本身**不接收**任何把账号密码、令牌、个人房间号写进代码或文档的提交。

## 致谢

本项目参考、借鉴了以下开源项目（均为宽松许可），在此致谢：

- [3beuml/ZZUPocket](https://github.com/3beuml/ZZUPocket)（MIT）——
  教务 / 一卡通接口层与项目骨架借鉴自该项目，UI、本校适配与后续功能为独立实现
- [Illustar0/ZZU.Py](https://github.com/Illustar0/ZZU.Py)、
  [Elykia093/ZZU-Electricity-Monitor](https://github.com/Elykia093/ZZU-Electricity-Monitor)、
  [rimrose/ZZU-Dorm-Electricity-Balance-Base](https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base)（MIT）——
  ZZUPocket 的接口协议来源，本项目经由 ZZUPocket 间接受益

## 许可证

[MIT](LICENSE)
Copyright © 2026 acdsh4869（HUELPocket）；含源自 ZZUPocket 的代码部分 Copyright © 2026 3beuml
