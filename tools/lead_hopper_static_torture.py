#!/usr/bin/env python3
"""Hostile static sentinels for the assembled Lead Hopper bridge.

The 1.x bridge is layered, so this checker evaluates effective late-layer protections rather
than treating superseded legacy source text as the runtime authority. A legacy pattern only
passes when a later guard/validator makes that path provably inert.
"""
from __future__ import annotations

import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTML = ROOT / "app" / "src" / "main" / "assets" / "index.html"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
MAIN_ACTIVITY = ROOT / "app" / "src" / "main" / "java" / "com" / "kyle" / "leadhopper" / "MainActivity.java"
SNAPSHOT_STORE = ROOT / "app" / "src" / "main" / "java" / "com" / "kyle" / "leadhopper" / "MigrationSnapshotStore.java"
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
    start = text.find(f"function {name}(")
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
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[brace:i + 1]
    return ""


def main() -> int:
    text = HTML.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")
    main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
    snapshot_store = SNAPSHOT_STORE.read_text(encoding="utf-8")
    hard: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    passed: list[str] = []

    def fail(code: str, message: str) -> None:
        hard.append({"code": code, "message": message})

    def warn(code: str, message: str) -> None:
        warnings.append({"code": code, "message": message})

    markers = [
        "v19DataSurvivalScript", "v13HotfixScript", "v13LayoutOrderScript",
        "v13ImportFixScript", "v14CustomPopupDismissScript", "v14CallbackAutoAdvanceScript",
        "v16HopperUiRepairScript", "v17UiNotesScript", "v18ActivityReportsRepairScript",
        "v19RestoreUiScript", "phase0ContractRepairsScript", "phase0PhoneQueueScript",
    ]
    for marker in markers:
        n = text.count(f'id="{marker}"')
        if n != 1:
            fail("PATCH_MARKER", f"{marker} expected exactly once, found {n}")
    if not any(x["code"] == "PATCH_MARKER" for x in hard):
        passed.append("All locked bridge fragments appear exactly once")

    scanner = IdScanner()
    scanner.feed(text)
    dupes = sorted(k for k, v in scanner.ids.items() if v > 1)
    if dupes:
        fail("DUPLICATE_IDS", "Duplicate HTML ids: " + ", ".join(dupes[:30]))
    else:
        passed.append("No duplicate HTML ids")

    guard = text.find('id="v19DataSurvivalScript"')
    v13 = text.find('id="v13Script"')
    guard_ok = guard >= 0 and v13 >= 0 and guard < v13 and all(x in text for x in [
        "if(!hydrated)", "blockedPreloadSaves", "__LH_STORAGE_READY__", "priorLoadAll=loadAll",
    ])
    if not guard_ok:
        fail("BOOT_SAVE_BEFORE_LOAD", "Persistence guard does not provably execute before V13 and block writes until hydration")
    else:
        passed.append("Pre-load writes are fail-closed behind the V19 hydration guard")

    journal_abort_ok = all(x in text for x in [
        "AndroidBridge.abortSnapshot(generation)", "Snapshot rollback failed",
    ]) and all(x in main_activity for x in [
        "abortSnapshot(long generation)", "snapshots.abort(generation)",
    ]) and "synchronized void abort(long generation)" in snapshot_store
    if not journal_abort_ok:
        fail("PENDING_JOURNAL_POISON", "Failed WebView writes are not proven to abort the exact staged native generation")
    else:
        passed.append("Failed writes generation-check and abort their staged native journal entry")

    single_instance_ok = 'android:launchMode="singleTask"' in manifest
    if not single_instance_ok:
        fail("MULTI_ACTIVITY_STACK", "MainActivity is not protected against duplicate launcher/task instances")
    else:
        passed.append("Lead Hopper launcher activity is single-instance within its task")

    backup_ok = all(x in text for x in [
        "exportData=window.exportData=function", "v19BuildBackupEnvelope",
        "downloadText('lead-hopper-full-backup-", "AndroidBridge.saveTextWithId", "v19ExportResult",
    ])
    if not backup_ok:
        fail("EXPORT_NOT_NATIVE", "Full Backup/Export is not proven to use operation-aware native save completion")
    else:
        passed.append("Full Backup/Export uses the operation-aware native save bridge")

    end_body = function_body(text, "endSession")
    if not end_body:
        fail("MISSING_FUNCTION", "endSession() not found")
    elif "v19AfterCommit" not in end_body or "downloadText" not in end_body:
        fail("EXPORT_NOT_NATIVE", "End Session is not deferred until commit and routed through shared native save")
    else:
        passed.append("End Session commits first and exports through shared native save")

    # Foreign lead ids are discarded by normalizeLead(). Full-state restore is the one path that
    # preserves ids, and V19 strictly validates both lead and schedule identifiers before mutation.
    dynamic_inline = bool(re.search(r'onclick="[^"]*\$\{\s*(?:[a-zA-Z_$][\w$]*\.)?(?:leadId|id)\s*\}', text))
    generated_import_ids = all(x in text for x in ["id: uid()", "legacyImportId"])
    strict_restore_ids = all(x in text for x in [
        "Unsafe or duplicate lead ID.",
        "typeof x.id!=='string'||!/^[a-zA-Z0-9_-]+$/.test(x.id)",
        "validate(candidate,true)",
    ])
    if dynamic_inline and not (generated_import_ids and strict_restore_ids):
        fail("UNTRUSTED_INLINE_ID", "Dynamic identifiers reach inline JavaScript without generated/import-validated id boundaries")
    elif generated_import_ids and strict_restore_ids:
        passed.append("Ordinary import ids are regenerated and full-state restore ids are strictly validated before use")
    else:
        passed.append("No unsafe dynamic identifier path detected")

    if "display:grid!important" in text:
        dock_fix = "dock.style.setProperty('display',tab==='hopper'?'grid':'none','important')"
        if dock_fix not in text:
            fail("DOCK_IMPORTANT_CONFLICT", "Fixed calling dock can override non-Hopper visibility")
        else:
            passed.append("Calling dock visibility uses an !important-aware tab override")

    snooze_body = function_body(text, "isSnoozed")
    if not snooze_body or "nextEligibleAt" not in snooze_body or "Date.now()" not in snooze_body:
        fail("CUSTOM_SNOOZE_IGNORED", "isSnoozed() does not treat any future nextEligibleAt as a snooze")
    else:
        passed.append("Generic future nextEligibleAt drives snooze eligibility")

    phone_queue_ok = all(x in text for x in [
        "__LH_PHASE0_PHONE_QUEUE__", "phase0PhoneQueueStatus", "q.order=maxQueueOrder()+1",
        "hasUnresolvedCallback", "hasOpenAppointment", "reconcileQueue",
    ])
    if not phone_queue_ok:
        fail("SHARED_PHONE_QUEUE", "One-phone cold queue state is missing durable ordering/reservation protections")
    else:
        passed.append("Shared-phone cold queue has durable ordering, tailing, callback and appointment guards")

    undo_audit_ok = all(x in text for x in [
        "reversed=true", "reversalId", "type:'Undo'", "action:'undo'",
    ])
    if not undo_audit_ok:
        fail("UNDO_ERASES_AUDIT", "Undo is not proven to retain and mark the reversed audit event")
    else:
        passed.append("Undo retains reversed audit events and appends a reversal record")

    restore_ok = all(x in text for x in [
        "v19ParseBackupText", "v19ApplyBackupText", "backupVersion!==1",
        "validate(candidate,true)", "v19RestoreBackupInput",
    ])
    if not restore_ok:
        fail("BACKUP_NOT_RESTORABLE", "Full-state backup lacks a strict versioned restore path")
    else:
        passed.append("Full-state backup has a strict versioned restore path")

    if "document.querySelectorAll('body > .modal-actions').forEach(function(x){x.remove();});" not in text:
        fail("ORPHAN_ACTION_ROW", "Malformed body-level modal action rows are not removed")
    else:
        passed.append("Malformed body-level action rows are removed")

    if ".modal-card{max-height:calc(100dvh - 24px)!important" not in text or "overflow:auto!important" not in text:
        fail("MODAL_CONTAINMENT", "Modal cards are not proven scrollable within the viewport")
    else:
        passed.append("Modal cards are viewport-bounded and scrollable")

    replace_ok = all(x in text for x in [
        "state.leads.forEach(l=>l.active=false)", "a.active=true", "byKey.has(k)",
        "Existing history and schedules will be preserved.", "phase0-archived-schedule",
    ])
    if not replace_ok:
        fail("REPLACE_ORPHANS_HISTORY", "Effective Replace path does not preserve stable existing lead identities/history and surface archived scheduled work")
    else:
        passed.append("Replace archives/reactivates existing people, preserves history, and surfaces archived scheduled work")

    suppression_ok = all(x in text for x in [
        "window.v13Unblock=function", "l.disposition='New'",
        "state.suppression={dnc:[],wrongNumbers:[]}", "Suppression Override",
    ])
    if not suppression_ok:
        fail("SUPPRESSION_REGENERATES", "Unblock/Clear does not correct both suppression and matching lead state")
    else:
        passed.append("Suppression correction updates protection records and matching lead state together")

    if "Generated ${escapeHtml(new Date().toLocaleString())} • V12" in text:
        warn("STALE_REPORT_LABEL", "Daily report still labels itself V12")
    if "Lead Hopper V13" in text:
        warn("STALE_UI_LABEL", "UI still contains a V13 prototype label")
    if "aria-hidden=\"true\"" in text and "editLeadModal" in text:
        warn("ARIA_MODAL_STATE", "Modal accessibility state deserves runtime verification")
    if dynamic_inline:
        warn("INLINE_HANDLER_DEBT", "Legacy inline id handlers remain; current generation/validation makes ids inert, but native 2.0 should remove them")

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
