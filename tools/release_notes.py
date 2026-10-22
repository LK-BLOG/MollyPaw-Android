#!/usr/bin/env python3
"""从 CHANGELOG.md 里抠出指定版本的正文，用于 GitHub Release。

CHANGELOG 与主仓库共用一份（在主仓库里），这个脚本会去找它；
找不到就回落成一行标题，不让发布流程因为「还没写 CHANGELOG」而中断。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def find_changelog() -> Path | None:
    candidates = [
        ROOT / "CHANGELOG.md",
        ROOT.parent / "MollyPaw" / "CHANGELOG.md",
        ROOT.parent / "MollyPaw Agent" / "CHANGELOG.md",
    ]
    for path in candidates:
        if path.is_file():
            return path
    return None


def notes(version: str) -> str:
    changelog = find_changelog()
    if changelog is None:
        return f"MollyPaw Android {version}"
    text = changelog.read_text(encoding="utf-8")
    match = re.search(rf"^## \[{re.escape(version)}\].*?$", text, re.M)
    if not match:
        return f"MollyPaw Android {version}"
    rest = text[match.end():]
    nxt = re.search(r"^## \[", rest, re.M)
    return (rest[: nxt.start()] if nxt else rest).strip()


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("用法: python tools/release_notes.py vX.Y.Z", file=sys.stderr)
        return 2
    print(notes(argv[1].lstrip("v")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
