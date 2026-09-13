# Lead Hopper Torture Contract

This file is the durable product/test contract for Lead Hopper. The Android Torture Lab should defend these behaviors before a build is treated as stable. When product behavior changes deliberately, update this contract and the tests together.

## Stabilization rule

The current stabilization cycle is a preservation-first release. Existing green calling behavior is frozen while data survival, compatibility, shell/layout, and safety defects are repaired. Do not casually redesign or rewrite working Hopper behavior while fixing unrelated defects.

The current working 1.x application must also be preserved as the golden behavioral reference for native 2.0. Native architecture may replace fragile machinery but must not casually add taps, screens, latency, abstractions, or workflow changes. Preservation is the default; departures require an explicit reason and evidence.

## Core calling workflow

- Lead Hopper is a local-first, one-lead-at-a-time calling station, not an autodialer or predictive dialer.
- **Call Now** must hand off to Android's system dialer and must never place a call automatically.
- A disposition is allowed only after that same lead has a recent Call Now event within the configured unlock window.
- **No Answer** saves the disposition, moves the worked lead to the tail, and automatically advances.
- **Not Interested** snoozes for the configured number of days.
- **Has State Farm** snoozes for its configured period.
- Any custom action that assigns a future eligibility date must remove that lead from ordinary Hopper eligibility until that date. Eligibility is one rule, not a special case per button.
- **Callback** creates a schedule item, removes the lead from ordinary eligibility until due, and advances only after the callback transaction has saved successfully. Cancel, invalid input, or failed persistence must not advance or partially apply.
- Due callbacks outrank ordinary leads. When two callbacks have the same due time, the callback that was originally scheduled first must come first.
- **Quote Appointment** opens the full appointment form, creates a scheduled appointment, and removes the lead from the cold hopper.
- Appointment spacing uses the configured spacing value; the exact boundary is allowed.
- **DNC**, **Wrong Number**, and **No English** remain excluded unless deliberately corrected.
- Previous must be able to revisit the last worked lead even if that lead is now snoozed or removed.
- Undo must restore the complete pre-action business state for dispositions, callbacks, appointments, suppression side effects, queue state, and timer state **without erasing the audit trail**. The original event remains recorded/identifiable as reversed and an Undo/reversal event is appended.
- Every lead should expose its last-call time unobtrusively.

## Persistence and data survival

A build fails the contract if closing, backgrounding, reloading, process death, or app upgrade loses or silently rewrites any of the following:

- leads and contact fields
- current lead/cursor and current index
- post-disposition held lead
- active tab
- call timer start timestamp
- previous-lead stack
- callbacks and appointments
- call history and activity history
- DNC/Wrong Number suppression
- No English records
- custom buttons/settings
- general lead notes

Boot order is **load -> migrate/backfill -> validate -> render -> save migrated state**. No bootstrap patch may save default state before persisted state has been loaded.

Storage failures must be visible. The UI must never imply success when durable storage failed. A failed save must not leave the user working against state that exists only in memory while the durable copy remains older.

The 1.7.7 bridge native migration journal and WebView state must either agree on a committed generation or fail closed/recover through a defined path. Unexplained divergence may not be guessed away.

## Import, replace, backup, and export

- Merge is the safe default import behavior.
- Replace must be explicit and must preserve permanent suppression.
- Replace means replace the **active calling roster**, not destroy historical people. People omitted by the replacement are archived/inactive rather than erased along with their notes/history.
- Replace must not orphan linked schedule/history records through unnecessary ID churn. Open callbacks/appointments attached to people removed from the active roster must be surfaced for deliberate resolution rather than silently deleted or stranded.
- Full backup JSON must be a versioned full-state snapshot and must actually be restorable as full application state.
- Restore must validate a backup before changing anything and must be atomic: malformed or unsavable backups leave the current app state unchanged.
- Activity CSV and Daily Report use Android's native save bridge.
- End Session and full Backup/Export must use the same reliable native/shared save path.
- CSV generation must quote correctly and must not create spreadsheet-formula execution hazards.
- Legitimate multiline quoted CSV fields must import correctly.
- Imported names, IDs, notes, addresses, email addresses, and other fields are untrusted text and must never become executable inline JavaScript.

## Shared phone-number contact clusters

Duplicate phone numbers are **not** automatically duplicate people.

When one normalized phone number is attached to multiple distinct names:

1. Preserve every person/lead record rather than deleting or merging the names away.
2. The ordinary cold Hopper presents only one calling opportunity for a normalized phone number at a time; duplicate names do not create duplicate cold calls to the same number.
3. Treat the records as a **contact cluster** for presentation.
4. Sort cluster members alphabetically by **last name, then first name**.
5. Display the alphabetically first person as the primary visible name.
6. Immediately beside that name, show a small `+N` badge where `N` is the number of additional names sharing that phone number.
7. The `+N` badge appears **only** when the number has two or more distinct names. Ordinary one-name/one-number leads show no extra badge or empty placeholder.
8. Tapping the badge opens a compact popover/sheet that **only lists the other names for reference**. It does not switch the active lead/person and cannot change which person receives notes, callbacks, appointments, history, email, or address edits.
9. Person-specific notes, history, appointment identity, email, and address remain attached to the individual lead unless explicitly changed.
10. **DNC is phone-wide** and applies to every name sharing the normalized number. **Wrong Number remains person + phone specific**, so a wrong-name record does not automatically suppress a different person who legitimately uses the same number.
11. Callback/appointment ownership remains attached to the active person unless a future product decision explicitly changes that rule.

Example: Bob Smith, Mary Smith, and Susan Carter share 555-1234. The default card displays `Susan Carter  +2`; tapping `+2` reveals Bob Smith and Mary Smith as informational names only. The cluster still contributes one ordinary cold-Hopper opportunity for 555-1234 rather than three duplicate cold calls.

## Hopper visual contract

The primary calling dock is deliberately opinionated:

- Call Now occupies a full row by itself and is the most prominent action.
- Row 2: **Not Interested** left, **No Answer** right, exact 50/50 split.
- Row 3: **Callback** left, **Quote Appointment** right, exact 50/50 split.
- Those four common-action buttons are exactly the same height as Call Now at both normal and short-screen breakpoints.
- DNC / No English / Wrong Number / custom-plus controls remain secondary.
- The calling dock must be visible on Hopper and hidden on All Leads, Schedule, Activity & Reports, and full-screen/modal management surfaces where it would cover content.
- Bottom controls must remain reachable above safe-area and dock padding; max scroll may never terminate with controls hidden beneath the dock.
- Modals must fit/scroll on small screens and with the software keyboard visible.
- No orphan Close/Save controls or malformed body-level action fragments.
- **All phone layouts use the existing approved full-color Lead Hopper logo as the header brand element instead of a squeezed text title.** Do not redesign the logo or alter the locked icon/monoglyph assets while making this header change.
- Approved full-color launcher artwork uses the same visual footprint/scale as the approved monochrome monoglyph so themed and unthemed launcher states have parity.
- Critical touch targets should meet the 48dp target wherever practical.
- Modal focus must not escape to background calling controls.
- Hopper actions must not be interactive before their JavaScript handlers and hydrated state are ready. A startup/process-restart tap may safely do nothing, but may not throw an uncaught handler error or mutate unhydrated state.

## Reports and activity

Activity & Reports must always provide a path back to Hopper and to the menu.

Daily Report is one standalone responsive/printable HTML file for the selected date and contains:

- calls
- unique leads called
- callbacks created
- appointments created
- operational conversion
- worked leads only
- chronological activity timeline
- schedule for the selected day

Activity/history must not silently truncate business records. Reversal/Undo auditing is retained rather than rewritten out of existence.

## Android shell contract

- WebView JavaScript and DOM storage enabled only as required by the local app.
- File URLs may load local packaged assets, but universal file-URL access remains disabled.
- Mixed content remains disabled.
- Safe Browsing remains enabled.
- External `tel:`, `mailto:`, `http:`, and `https:` intents are handed to Android appropriately.
- File import uses Android's document picker.
- Text/HTML/CSV/JSON export uses Android's create-document flow.
- Hardware back must not strand the user on SPA pages; app-level navigation must provide deterministic escape paths.
- The release icon/monoglyph remains locked to the approved asset and certificate identity.
- The declared minimum API is a real compatibility promise: the app must execute its production JavaScript on the minimum supported WebView rather than merely install there.

## Torture Lab expectations

Every serious build should be subjected to:

- minimum supported Android API and current target API emulator runs
- real packaged WebView instrumentation
- persistence/reload regression fixtures
- deterministic queue/callback/appointment contract tests
- generic future-eligibility/custom-snooze tests
- shared-phone cluster and one-cold-opportunity tests
- Replace archive + unresolved-schedule resolution tests
- Undo/reversal audit-retention tests
- backup -> wipe -> restore -> compare round-trip testing
- forced storage-write failure/rollback testing
- process death/relaunch
- random Monkey input and logcat crash/ANR/uncaught-JS scanning
- multiple viewport sizes/densities
- large font scaling
- screenshots of Hopper, menu, All Leads, Schedule, Reports, callback modal, appointment modal, No English modal, and long-content states
- package payload checks proving the tested HTML is the same HTML packaged into release
- static sentinels for data-loss, unsafe export, executable imported identifiers, duplicate IDs, and fixed-dock regressions
- a populated same-signer 1.x -> 1.7.7 Bridge upgrade before Phase 0 is declared complete

The Torture Lab is intentionally allowed to be red while known blocking defects remain. A green result should mean something.
