#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PR 安全体检：扫出「定时任务 + 外发请求 + 凭据读取 + 权限扩张」这类组合。

用法：
    python tools/check_pr_security.py            # 扫整个工程
    python tools/check_pr_security.py --diff     # 只看 git 未提交改动涉及的文件

判定逻辑不做"单点报错"，而是给出线索 + 风险组合提示：
单个 Base64 字符串不算问题，但「AlarmManager + Base64 + POST 到新域名」就是典型外发后门。
"""

import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 允许联网的域名白名单（新增域名必须人工确认）
ALLOWED_HOSTS = {
    "userapi.qiekj.com",     # 胖乖业务网关
    "h5.qiekj.com",          # 胖乖 H5
    "cas.huel.edu.cn",       # 学校 CAS
    "xk.huel.edu.cn",        # 正方教务
    "yktwx2.huel.edu.cn",    # 一卡通
    "v1.chaoxing.com",       # 学习通
    "github.com",            # 检查更新
    "api.github.com",
    "maven.aliyun.com",      # gradle 仓库
    "developer.android.com", # lint 基线里的文档链接
}

# 允许的权限基线（超出即需人工确认）
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.VIBRATE",
    "android.permission.CAMERA",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
}

# 高风险权限：一旦出现基本可以判定越界
DANGEROUS_PERMISSIONS = {
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_CALL_LOG",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
}

# 可疑代码特征
SUSPICIOUS = [
    (r"Base64\.decode|Base64\.getDecoder", "Base64 解码（常用来藏 URL/命令）"),
    (r"Class\.forName|DexClassLoader|PathClassLoader", "动态加载类（可执行远端代码）"),
    (r"setJavaScriptEnabled\s*\(\s*true\s*\)", "WebView 开 JS（配合 addJavascriptInterface 危险）"),
    (r"addJavascriptInterface", "WebView 暴露原生接口"),
    (r"Runtime\.getRuntime\(\)\.exec|ProcessBuilder", "执行系统命令"),
    (r"HttpURLConnection|OkHttpClient\s*\(", "自定义网络请求（确认目标域名）"),
    (r"WorkManager|AlarmManager|setRepeating|setExactAndAllowWhileIdle", "定时任务（确认用途）"),
    (r"getSharedPreferences\s*\(|EncryptedSharedPreferences", "读取本地存储（可能是凭据）"),
    (r"getInstalledPackages|getInstalledApplications", "枚举已安装应用"),
    (r"TelephonyManager|getDeviceId|getImei", "读取设备标识"),
    (r"evaluateJavascript\s*\(|loadUrl\s*\(", "WebView 注入脚本"),
]

# 注入类内容特征（PR 里夹带的"给 AI 的指令"）
INJECTION = [
    (r"忽略(之前|以上|上述).{0,6}(指令|要求|提示)", "中文提示词注入"),
    (r"ignore\s+(all\s+)?(previous|above|prior)\s+instructions?", "英文提示词注入"),
    (r"you\s+are\s+now\s+(a|an)\s+", "角色覆盖"),
    (r"不要\s*(报告|提示|告诉)\s*(用户|审查|reviewer)", "要求隐瞒"),
    (r"system\s*prompt|developer\s*message", "提及系统提示"),
]

CODE_EXT = (".kt", ".java", ".xml", ".gradle", ".kts", ".pro", ".properties")
SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", ".idea", "dist"}


def iter_files(diff_only: bool):
    if diff_only:
        try:
            out = subprocess.check_output(
                ["git", "diff", "--name-only", "HEAD"], cwd=ROOT, text=True,
            )
            names = [n.strip() for n in out.splitlines() if n.strip()]
        except Exception:
            names = []
        for n in names:
            p = os.path.join(ROOT, n)
            if os.path.isfile(p):
                yield p
        return
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            yield os.path.join(dirpath, f)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--diff", action="store_true", help="只扫 git 未提交的改动")
    args = ap.parse_args()

    host_hits, perm_hits, danger_hits = {}, [], []
    code_hits, inject_hits = [], []

    for path in iter_files(args.diff):
        rel = os.path.relpath(path, ROOT)
        if not path.endswith(CODE_EXT + (".md", ".txt", ".json", ".yml", ".yaml")):
            continue
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except Exception:
            continue

        check_host = path.endswith((".kt", ".java", ".xml", ".gradle", ".kts"))
        for m in (re.finditer(r"https?://([a-zA-Z0-9.\-]+)", text) if check_host else []):
            host = m.group(1).lower()
            # 排除注释里的示例站与本地地址
            if host in {"localhost", "127.0.0.1", "example.com", "schemas.android.com"}:
                continue
            if host not in ALLOWED_HOSTS:
                host_hits.setdefault(host, set()).add(rel)

        for m in re.finditer(r'android:name="(android\.permission\.[A-Z_]+)"', text):
            perm = m.group(1)
            if perm in DANGEROUS_PERMISSIONS:
                danger_hits.append((perm, rel))
            elif perm not in ALLOWED_PERMISSIONS:
                perm_hits.append((perm, rel))

        if path.endswith(".kt") or path.endswith(".java"):
            for pat, desc in SUSPICIOUS:
                if re.search(pat, text):
                    code_hits.append((desc, rel))

        # 注入检测：源码/文档里夹带的"对 AI 说的话"
        if path.endswith((".md", ".txt", ".kt", ".java", ".xml")):
            for pat, desc in INJECTION:
                for m in re.finditer(pat, text, flags=re.IGNORECASE):
                    line = text[:m.start()].count("\n") + 1
                    inject_hits.append((desc, rel, line))

    def dump(title, rows, fmt):
        print("\n== " + title)
        if not rows:
            print("   无")
            return
        for r in rows:
            print("   " + fmt(r))

    print("扫描范围：" + ("git 未提交改动" if args.diff else "整个工程"))
    dump("新增/未授权域名", sorted(host_hits.items()), lambda r: f"{r[0]}  ← {', '.join(sorted(r[1]))}")
    dump("高风险权限（基本可判定越界）", sorted(set(danger_hits)), lambda r: f"{r[0]}  ← {r[1]}")
    dump("超出基线的权限", sorted(set(perm_hits)), lambda r: f"{r[0]}  ← {r[1]}")
    dump("可疑代码特征（需人工判断）", sorted(set(code_hits)), lambda r: f"{r[0]}  ← {r[1]}")
    dump("疑似提示词注入", sorted(set(inject_hits)), lambda r: f"{r[0]}  @ {r[1]}:{r[2]}")

    # 组合判定：定时任务 + 网络请求 + 凭据读取 = 典型外发后门
    tags = {d for d, _ in code_hits}
    combo = (
        any("定时" in t for t in tags)
        and any("网络" in t for t in tags)
        and any("存储" in t for t in tags)
    )
    print("\n== 结论")
    if not args.diff and (combo or code_hits):
        print("   整仓扫描会命中既有实现（提醒、WebView、prefs），属基线噪声；")
        print("   判断 PR 是否夹带后门请用 --diff 只看本次改动。")
    if danger_hits or host_hits:
        print("   发现越界项，合并前必须人工确认")
    elif combo:
        print("   命中「定时 + 联网 + 读本地存储」组合，高度疑似外发后门，务必逐行看")
    elif inject_hits:
        print("   发现疑似提示词注入文本，检查是否有人试图操纵审查者")
    else:
        print("   未发现明显风险项（仍建议看一遍 diff）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
