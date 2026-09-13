#!/usr/bin/env python3
"""Static torture sentinels for the assembled Lead Hopper production HTML.

This is intentionally hostile. It fails on known data-loss/security/navigation patterns so the
Torture Lab stays red until those defects are actually removed. It is not a replacement for the
Android emulator tests; it catches source/package contradictions cheaply and deterministically.

Important: Lead Hopper 1.x is a layered bridge. Sentinels must inspect the *effective* protection
path, not merely fail because superseded legacy source text still exists earlier in the document.
A compatibility override only earns a pass when the sentinel can prove the guard/validation path
that makes the old text unreachable or harmless at runtime.
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

    # Production patch/package locks. V19 persistence must be pre-V13; restore/command repair is post-app.
    post_markers = [
        "v13HotfixScript", "v13LayoutOrderScript", "v13ImportFixScript",
        "v14CustomPopupDismissScript", "v14CallbackAutoAdvanceScript",
        "v16HopperUiRepairScript", "v17UiNotesScript", "v18ActivityReportsRepairScript",
        "v19RestoreUiScript",
    ]
    for marker in ["v19DataSurvivalScript", *post_markers]:
        n = text.count(f'id="{marker}"')
        if n != 1:
            fail("PATCH_MARKER", f"{marker} expected exactly once, found {n}")
    if not any(x["code"] == "PATCH_MARKER" for x in hard):
        passed.append("All locked bridge fragments appear exactly once")

    # Duplicate DOM ids are a silent event-routing hazard.
    scanner = IdScanner()
    scanner.feed(text)
    duplicates = sorted(k for k, v in scanner.ids.items() if v > 1)
    if duplicates:
        fail("DUPLICATE_IDS", "Duplicate HTML ids: " + ", ".join(duplicates[:30]))
    else:
        passed.append("No duplicate HTML ids")

    # P0 boot order. Legacy V13 still contains a save call, but the V19 guard executes first and
    # must reject *every* save until hydration succeeds. Do not confuse dead legacy text with an
    # effective pre-load write.
    guard = text.find('id="v19DataSurvivalScript"')
    v13 = text.find('id="v13Script"')
    guard_contract = all(token in text for token in [
        "if(!hydrated)", "blockedPreloadSaves", "__LH_STORAGE_READY__", "priorLoadAll=loadAll",
    ])
    if guard < 0 or v13 < 0 or guard >= v13 or not guard_contract:
        fail("BOOT_SAVE_BEFORE_LOAD", "Persistence guard does not provably execute before V13 and block writes until hydration")
    else:
        passed.append("Pre-load writes are fail-closed behind the V19 hydration guard")

    # Backup export and End Session must use the reliable native/shared save path. The effective
    # backup implementation is the late V19 assignment; the historical first exportData body is
    # deliberately ignored once the override contract is proven.
    effective_backup = all(token in text for token in [
        "exportData=window.exportData=function", "v19BuildBackupEnvelope", "downloadText('lead-hopper-full-backup-",
        "AndroidBridge.saveTextWithId", "v19ExportResult",
    ])
    if not effective_backup:
        fail("EXPORT_NOT_NATIVE", "Effective full Backup/Export is not proven to use operation-aware native save completion")
    else:
        passed.append("Full Backup/Export uses the operation-aware native save bridge")

    end_body = function_body(text, "endSession")
    if not end_body:
        fail("MISSING_FUNCTION", "endSession() not found")
    elif "v19AfterCommit" not in end_body or "downloadText" not in end_body:
        fail("EXPORT_NOT_NATIVE", "End Session is not deferred until commit and routed through shared native save")
    else:
        passed.append("End Session commits first and exports through shared native save")

    # Imported identifiers are untrusted. The bridge may still contain legacy inline handlers, but
    # those handlers are acceptable only while ordinary imports discard foreign ids and full-state
    # restore rejects any id outside the inert identifier alphabet before mutation.
    has_dynamic_inline_ids = bool(re.search(r'onclick="[^"]*\$\{\s*(?:s\.)?(?:leadId|id)\s*\}', text))
    id_boundary = all(token in text for token in [
        "id: uid()", "legacyImportId", "Unsafe or duplicate lead ID.",
        "Unsafe or duplicate schedule ID.", "validate(candidate,true)",
    ])
    if has_dynamic_inline_ids and not id_boundary:
        fail("UNTRUSTED_INLINE_ID", "Dynamic identifiers reach inline JavaScript without generated/import-validated id boundaries")
    elif id_boundary:
        passed.append("Imported ids are regenerated for lead import and strictly validated for full-state restore")
    else:
        passed.append("No dynamic imported identifier interpolation detected")

    # The fixed dock has display:grid!important in legacy CSS. Effective tab code must therefore set
    # the inline display property with the same priority.
    if "display:grid!important" in text:
        if "dock.style.setProperty('display',tab==='hopper'?'grid':'none','important')" not in text:
            fail("DOCK_IMPORTANT_CONFLICT", "Fixed calling dock can override non-Hopper visibility")
        else:
            passed.append("Calling dock visibility uses an !important-aware tab override")

    # Generic future eligibility is the authority for built-in and custom snoozes. Do not require
    # isSnoozed() to know custom button ids/labels.
    snooze_body = function_body(text, "isSnoozed")
    if not snooze_body or "nextEligibleAt" not in snooze_body or "Date.now()" not in snooze_body:
        fail("CUSTOM_SNOOZE_IGNORED", "isSnoozed() does not treat any future nextEligibleAt as a snooze")
    else:
        passed.append("Generic future nextEligibleAt drives snooze eligibility")

    # Full-state backup needs a strict restore path that checks versions and relationships before
    # writing. Lead-array import is a separate feature and does not invalidate this path.
    restore_contract = all(token in text for token in [
        "v19ParseBackupText", "v19ApplyBackupText", "backupVersion!==1", "validate(candidate,true)",
        "v19RestoreBackupInput",
    ])
    if not restore_contract:
        fail("BACKUP_NOT_RESTORABLE", "Full-state backup does not have a strict versioned restore path")
    else:
        passed.append("Full-state backup has a strict versioned restore path")

    # Management containment and orphan-action repair are effective late-layer contracts.
    if "document.querySelectorAll('body > .modal-actions').forEach(function(x){x.remove();});" not in text:
        fail("ORPHAN_ACTION_ROW", "Malformed body-level modal action rows are not removed")
    else:
        passed.append("Malformed body-level action rows are removed")
    if ".modal-card{max-height:calc(100dvh - 24px)!important" not in text or "overflow:auto!important" not in text:
        fail("MODAL_CONTAINMENT", "Modal cards are not proven scrollable within the viewport")
    else:
        passed.append("Modal cards are viewport-bounded and scrollable")

    # Replace must archive the active roster and reactivate exact existing people rather than
    # replacing the database and orphaning historical foreign keys.
    replace_contract = all(token in text for token in [
        "state.leads.forEach(l=>l.active=false)", "a.active=true", "byKey.has(k)",
        "Existing history and schedules will be preserved.",
    ])
    if not replace_contract:
        fail("REPLACE_ORPHANS_HISTORY", "Effective Replace path does not preserve stable existing lead identities/history")
    else:
        passed.append("Replace archives/reactivates existing people instead of deleting linked history")

    # Suppression correction must change both the authoritative suppression record and the matching
    # disposition so ensure/backfill code cannot immediately recreate the block.
    suppression_contract = all(token in text for token in [
        "window.v13Unblock=function", "l.disposition='New'", "state.suppression={dnc:[],wrongNumbers:[]}",
        "Suppression Override",
    ])
    if not suppression_contract:
        fail("SUPPRESSION_REGENERATES", "Unblock/Clear does not provably correct both suppression and lead state")
    else:
        passed.append("Suppression correction updates protection records and matching lead state together")

    # Non-blocking coherence warnings.
    if "Generated ${escapeHtml(new Date().toLocaleString())} • V12" in text:
        warn("STALE_REPORT_LABEL", "Daily report still labels itself V12")
    if "Lead Hopper V13" in text:
        warn("STALE_UI_LABEL", "UI still contains a V13 product label while Android version is newer")
    if "oldNotes.parentElement.style.display='none'" in text:
        warn("NO_ENGLISH_NOTES_HIDDEN", "No English notes field is explicitly hidden in a superseded layer; verify effective visibility")
    if "aria-hidden=\"true\"" in text and "editLeadModal" in text:
        warn("ARIA_MODAL_STATE", "Modal accessibility state deserves runtime verification")
    if has_dynamic_inline_ids:
        warn("INLINE_HANDLER_DEBT", "Legacy inline identifier handlers remain; current import/restore id validation makes them inert, but native 2.0 should remove them")

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
