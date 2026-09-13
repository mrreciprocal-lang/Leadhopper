#!/usr/bin/env python3
"""Static torture sentinels for the assembled Lead Hopper production HTML.

This is intentionally hostile. It fails on known data-loss/security/navigation patterns so the
Torture Lab stays red until those defects are actually removed. It is not a replacement for the
Android emulator tests; it catches source/package contradictions cheaply and deterministically.
"""
from __future__ import annotations

import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTML = ROOT / "app" / "src" / "main" / "assets" / "index.html"
REPORT = ROOT / "torture-static-report.json"


class IdScanner(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: dict[str, int] = {}

    def handle_starttag(self, tag, attrs):
        for key, value in attrs:
            if key == "id" and value:
                self.ids[value] = self.ids.get(value, 0) + 1


def function_body(text: str, name: str) -> str:
    marker = f"function {name}("
    start = text.find(marker)
    if start < 0:
        return ""
    brace = text.find("{", start)
    if brace < 0:
        return ""
    depth = 0
    quote = None
    escape = False
    for i in range(brace, len(text)):
        ch = text[i]
        if quote:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == quote:
                quote = None
            continue
        if ch in "'\"`":
            quote = ch
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[brace : i + 1]
    return ""


def main() -> int:
    text = HTML.read_text(encoding="utf-8")
    hard: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    passed: list[str] = []

    def fail(code: str, message: str) -> None:
        hard.append({"code": code, "message": message})

    def warn(code: str, message: str) -> None:
        warnings.append({"code": code, "message": message})

    # Production patch/package locks.
    for marker in [
        "v13HotfixScript", "v13LayoutOrderScript", "v13ImportFixScript",
        "v14CustomPopupDismissScript", "v14CallbackAutoAdvanceScript",
        "v16HopperUiRepairScript", "v17UiNotesScript", "v18ActivityReportsRepairScript",
    ]:
        n = text.count(f'id="{marker}"')
        if n != 1:
            fail("PATCH_MARKER", f"{marker} expected exactly once, found {n}")
    if not hard:
        passed.append("All locked patch fragments appear exactly once")

    # Duplicate DOM ids are a silent event-routing hazard.
    scanner = IdScanner()
    scanner.feed(text)
    duplicates = sorted(k for k, v in scanner.ids.items() if v > 1)
    if duplicates:
        fail("DUPLICATE_IDS", "Duplicate HTML ids: " + ", ".join(duplicates[:30]))
    else:
        passed.append("No duplicate HTML ids")

    # P0 boot-order persistence wipe: any unconditional save in pre-init patch bootstrap is forbidden.
    init_call = min([p for p in [text.find("DOMContentLoaded',init"), text.find('DOMContentLoaded",init')] if p >= 0] or [len(text)])
    dangerous_boot = text.find("ensureV13();saveAll()")
    if dangerous_boot >= 0 and dangerous_boot > init_call:
        # Patches are appended after base script; they execute during parsing before DOMContentLoaded/init/loadAll.
        fail("BOOT_SAVE_BEFORE_LOAD", "V13 patch still calls ensureV13();saveAll() during parsing before init()/loadAll()")
    else:
        passed.append("No known pre-load V13 save pattern")

    # Backup/End Session must use the Android bridge, not Blob/object-URL download hacks.
    for fn in ["exportData", "endSession"]:
        body = function_body(text, fn)
        if not body:
            fail("MISSING_FUNCTION", f"{fn}() not found")
        elif "Blob(" in body or "URL.createObjectURL" in body:
            fail("LEGACY_BROWSER_EXPORT", f"{fn}() still uses Blob/object URL instead of AndroidBridge/shared downloadText")
        elif "downloadText" not in body and "AndroidBridge.saveText" not in body:
            fail("EXPORT_NOT_NATIVE", f"{fn}() does not visibly route through native/shared save path")
        else:
            passed.append(f"{fn} uses native/shared export path")

    # Inline JS built from imported identifiers is code injection, even in a local-first app.
    if re.search(r'onclick="[^"]*\$\{\s*s\.leadId\s*\}', text):
        fail("UNTRUSTED_INLINE_ID", "Schedule UI interpolates s.leadId into inline onclick JavaScript")
    else:
        passed.append("No schedule lead id interpolation into inline onclick")

    # The fixed dock must not be unconditionally !important-visible while JS tries inline display:none.
    if ".actions{" in text and "display:grid!important" in text and "dock.style.display=tab==='hopper'?'grid':'none'" in text:
        fail("DOCK_IMPORTANT_CONFLICT", "Fixed calling dock has display:grid!important while tab code only sets inline display, so non-Hopper pages can be covered")
    else:
        passed.append("No known fixed-dock !important visibility conflict")

    # Custom snooze must participate in eligibility. Current engine only recognizes hard-coded dispositions.
    snooze_body = function_body(text, "isSnoozed")
    if snooze_body and "customButtons" not in snooze_body and "snoozeDays" not in snooze_body:
        fail("CUSTOM_SNOOZE_IGNORED", "isSnoozed() does not recognize custom snooze dispositions")

    # Full-state backup must have an actual restore path, not only lead-array import.
    if "JSON.stringify(state,null,2)" in text:
        # doImport maps JSON as lead rows; there is no state-object restore marker in current build.
        do_import = function_body(text, "doImport")
        if do_import and "JSON.parse(txt).map" in do_import:
            fail("BACKUP_NOT_RESTORABLE", "Full-state JSON export exists but JSON import treats input as a lead array")

    # Non-blocking coherence warnings.
    if "Generated ${escapeHtml(new Date().toLocaleString())} • V12" in text:
        warn("STALE_REPORT_LABEL", "Daily report still labels itself V12")
    if "Lead Hopper V13" in text:
        warn("STALE_UI_LABEL", "UI still contains a V13 product label while Android version is newer")
    if "oldNotes.parentElement.style.display='none'" in text:
        warn("NO_ENGLISH_NOTES_HIDDEN", "No English notes field is explicitly hidden")
    if "aria-hidden=\"true\"" in text and "editLeadModal" in text:
        warn("ARIA_MODAL_STATE", "Modal accessibility state deserves runtime verification")

    report = {
        "hard_failures": hard,
        "warnings": warnings,
        "passes": passed,
        "hard_failure_count": len(hard),
        "warning_count": len(warnings),
    }
    REPORT.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(json.dumps(report, indent=2))
    if hard:
        print(f"\nSTATIC TORTURE: FAIL ({len(hard)} blocking findings)", file=sys.stderr)
        return 1
    print("\nSTATIC TORTURE: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
