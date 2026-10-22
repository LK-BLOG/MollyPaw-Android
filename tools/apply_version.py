#!/usr/bin/env python3
"""按 tag 设置 Android 应用的版本号。

版本号的**唯一真源是 tag** —— 代码里不维护一份"当前版本"，
所以不会出现「桌面端 0.0.0.1.5、APK 是 0.1.0」这种漂移。

用法：
    python tools/apply_version.py v0.0.0.1.6
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "app" / "build.gradle.kts"


def normalize_version(tag: str) -> str:
    """从 tag 里取纯版本号。

    这个项目的 tag 前缀很乱（v / Beta- / beta- 都出现过），
    版本名里不该带前缀 —— 应该是纯 "0.0.0.2.0"。
    """
    import re

    match = re.search(r"(\d[\d.]*)$", tag.strip())
    return match.group(1) if match else tag.strip().lstrip("v")


def version_code(version: str) -> int:
    """0.0.0.1.0 -> 100（点分段拼十进制，单调递增且可排序）。"""
    parts = [p for p in version.split(".") if p.isdigit()]
    if not parts:
        raise SystemExit(f"版本号里没有数字段：{version}")
    code = 0
    for part in parts:
        code = code * 100 + int(part)
    return code


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("用法: python tools/apply_version.py beta-0.0.0.2.0", file=sys.stderr)
        return 2

    version = normalize_version(argv[1])
    code = version_code(version)

    text = BUILD.read_text(encoding="utf-8")
    text, n1 = re.subn(r'versionCode\s*=\s*\d+', f'versionCode = {code}', text)
    text, n2 = re.subn(r'versionName\s*=\s*"[^"]*"', f'versionName = "{version}"', text)
    if not n1 or not n2:
        print("没找到 versionCode / versionName，检查 app/build.gradle.kts")
        return 1

    BUILD.write_text(text, encoding="utf-8")
    print(f"版本已设置: versionName={version} versionCode={code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
