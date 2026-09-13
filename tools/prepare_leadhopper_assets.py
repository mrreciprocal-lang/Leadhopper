#!/usr/bin/env python3
"""Rebuild the exact production index.html from the locked V13 base plus surgical patches.

Both release CI and the Android torture lab call this file so the thing being tested is the
thing being shipped. Add future patch fragments here once, in explicit order.
"""
from __future__ import annotations

import base64
import gzip
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
INDEX = ASSETS / "index.html"

PRE_V13_PATCH = ("v19DataSurvivalScript", "v19_data_survival.html")

PATCHES = [
    ("v13HotfixScript", "v13_hotfix.html"),
    ("v13LayoutOrderScript", "v13_layout_order.html"),
    ("v13ImportFixScript", "v13_import_fix.html"),
    ("v14CustomPopupDismissScript", "v14_custom_popup_dismiss.html"),
    ("v14CallbackAutoAdvanceScript", "v14_callback_autoadvance.html"),
    ("v16HopperUiRepairScript", "v16_hopper_ui_repair.html"),
    ("v17UiNotesScript", "v17_ui_notes.html"),
    ("v18ActivityReportsRepairScript", "v18_activity_reports_repair.html"),
    ("v19RestoreUiScript", "v19_restore_ui.html"),
]


def restore_base() -> str:
    canonical = ASSETS / "bridge_base.html"
    if canonical.exists():
        return canonical.read_text(encoding="utf-8")
    parts = sorted(ASSETS.glob("index.html.gz.b64.part*"))
    if not parts:
        raise RuntimeError("No compressed V13 base parts found")
    encoded = "".join(p.read_text(encoding="utf-8").replace("\r", "").replace("\n", "") for p in parts)
    raw = base64.b64decode(encoded)
    return gzip.decompress(raw).decode("utf-8")


def apply_api26_compatibility(document: str) -> str:
    """Keep the bridge parseable by the minimum supported WebView.

    The canonicalized 1.x bridge inherited two optional-chaining expressions from a late hotfix.
    API 26's stock WebView predates optional chaining, so a single `?.` can make that entire script
    block fail to parse before any compatibility override has a chance to run. Rewrite only those
    known expressions while the bridge remains in stabilization; native 2.0 will not carry this
    JavaScript compatibility layer forward.
    """
    replacements = {
        "l?.id": "(l&&l.id)",
        "list[idx]?.id": "(list[idx]&&list[idx].id)",
    }
    for old, new in replacements.items():
        document = document.replace(old, new)
    if "?." in document:
        raise RuntimeError("Production bridge still contains optional chaining unsupported by the API 26 WebView contract")
    return document


def insert_before_v13(document: str, fragment: str) -> str:
    marker = '<script id="v13Script">'
    if document.count(marker) != 1:
        raise RuntimeError("Expected exactly one V13 bootstrap script marker")
    return document.replace(marker, fragment + "\n" + marker, 1)


def insert_before_final_body(document: str, fragment: str) -> str:
    head, sep, tail = document.rpartition("</body>")
    if not sep:
        raise RuntimeError("Final </body> tag not found")
    return head + fragment + "\n</body>" + tail


def validate(text: str) -> None:
    init = text.find("function init()")
    v13 = text.find('<script id="v13Script">')
    final_body = text.rfind("</body>")
    final_html = text.rfind("</html>")
    if init < 0 or v13 < 0 or final_body < 0 or final_html < final_body:
        raise RuntimeError("Production HTML skeleton is malformed")
    if not text.rstrip().endswith("</html>"):
        raise RuntimeError("Production HTML must end with </html>")

    pre_marker, _ = PRE_V13_PATCH
    pre_token = f'id="{pre_marker}"'
    if text.count(pre_token) != 1 or not (init < text.find(pre_token) < v13):
        raise RuntimeError("Data survival patch must execute exactly once before V13 bootstrap")

    for marker, _ in PATCHES:
        token = f'id="{marker}"'
        count = text.count(token)
        if count != 1:
            raise RuntimeError(f"Patch marker {marker} expected exactly once, found {count}")
        pos = text.find(token)
        if pos <= v13 or pos >= final_body:
            raise RuntimeError(f"Patch marker {marker} is outside expected post-app location")

    required = [
        "window.openImport=showImportModal",
        "v14CloseCustomPopup",
        "__LH_V14_CALLBACK_AUTO_ADVANCE__",
        "__LH_V16_HOPPER_UI_REPAIR__",
        "__LH_V17_UI_NOTES__",
        "__LH_V18_ACTIVITY_REPORTS_REPAIR__",
        "__LH_V19_DATA_SURVIVAL__",
        "__LH_V19_RESTORE_UI__",
        "AndroidBridge.saveText",
        "v18MenuHome",
        "v18ActivityNav",
        "v17LeadNotesInput",
        "lead-hopper-full-backup",
        "v19RestoreBackupInput",
        "grid-template-columns:repeat(4,minmax(0,1fr))!important",
        "height:78px!important",
        "height:64px!important",
    ]
    missing = [needle for needle in required if needle not in text]
    if missing:
        raise RuntimeError("Production HTML missing required contracts: " + ", ".join(missing))


def main() -> None:
    text = apply_api26_compatibility(restore_base())
    pre_marker, pre_filename = PRE_V13_PATCH
    pre_fragment = apply_api26_compatibility((ASSETS / pre_filename).read_text(encoding="utf-8"))
    if f'id="{pre_marker}"' not in text:
        text = insert_before_v13(text, pre_fragment)
    for marker, filename in PATCHES:
        fragment = apply_api26_compatibility((ASSETS / filename).read_text(encoding="utf-8"))
        if f'id="{marker}"' not in text:
            text = insert_before_final_body(text, fragment)
    validate(text)
    INDEX.write_text(text, encoding="utf-8")
    print(f"Prepared {INDEX.relative_to(ROOT)}: {len(text.encode('utf-8'))} bytes, 1 pre-init guard, {len(PATCHES)} post-app patch fragments")


if __name__ == "__main__":
    main()
