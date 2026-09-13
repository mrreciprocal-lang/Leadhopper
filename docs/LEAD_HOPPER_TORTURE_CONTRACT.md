# Lead Hopper Torture Contract

This file is the durable product/test contract for Lead Hopper. The Android Torture Lab should defend these behaviors before a build is treated as stable. When product behavior changes deliberately, update this contract and the tests together.

## Core calling workflow

- Lead Hopper is a local-first, one-lead-at-a-time calling station, not an autodialer or predictive dialer.
- **Call Now** must hand off to Android's system dialer and must never place a call automatically.
- A disposition is allowed only after that same lead has a recent Call Now event within the configured unlock window.
- **No Answer** saves the disposition, moves the worked lead to the tail, and automatically advances.
- **Not Interested** snoozes for the configured number of days.
- **Has State Farm** snoozes for its configured period.
- **Callback** creates a schedule item, removes the lead from ordinary eligibility until due, and advances after save.
- Due callbacks outrank ordinary leads. When two callbacks have the same due time, the callback that was originally scheduled first must come first.
- **Quote Appointment** opens the full appointment form, creates a scheduled appointment, and removes the lead from the cold hopper.
- Appointment spacing uses the configured spacing value; the exact boundary is allowed.
- **DNC**, **Wrong Number**, and **No English** remain excluded unless deliberately corrected.
- Previous must be able to revisit the last worked lead even if that lead is now snoozed or removed.
- Undo must restore the complete pre-action transaction for dispositions, callbacks, appointments, suppression side effects, queue state, activity/history, and timer state.
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

Storage failures must be visible. The UI must never imply success when durable storage failed.

## Import, replace, backup, and export

- Merge is the safe default import behavior.
- Replace must be explicit and must preserve permanent suppression.
- Replace must not orphan linked schedule/history records through unnecessary ID churn.
- Full backup JSON must actually be restorable as full application state.
- Activity CSV and Daily Report use Android's native save bridge.
- End Session and full Backup/Export must use the same reliable native/shared save path.
- CSV generation must quote correctly and must not create spreadsheet-formula execution hazards.
- Legitimate multiline quoted CSV fields must import correctly.
- Imported names, IDs, notes, addresses, email addresses, and other fields are untrusted text and must never become executable inline JavaScript.

## Shared phone-number contact clusters

Duplicate phone numbers are **not** automatically duplicate people.

When one normalized phone number is attached to multiple distinct names:

1. Preserve every person/lead record rather than deleting or merging the names away.
2. Treat the records as a **contact cluster** for presentation.
3. Sort cluster members alphabetically by **last name, then first name**.
4. Display the alphabetically first person as the primary visible name.
5. Immediately beside that name, show a small `+N` badge where `N` is the number of additional names sharing that phone number.
6. The `+N` badge appears **only** when the number has two or more distinct names. Ordinary one-name/one-number leads show no extra badge or empty placeholder.
7. Tapping the badge opens a compact popover/sheet listing the other names in the cluster. Selecting another name switches the active identity while retaining the shared phone number.
8. Person-specific notes, history, appointment identity, email, and address remain attached to the individual lead unless explicitly changed.
9. Phone-level safety propagates across the cluster: **DNC and Wrong Number apply to the normalized phone number across every name sharing it.**
10. Callback/appointment ownership remains attached to the selected person unless a future product decision explicitly changes that rule.

Example: Bob Smith, Mary Smith, and Susan Carter share 555-1234. The default card displays `Susan Carter  +2`; tapping `+2` reveals Bob Smith and Mary Smith.

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
- Header/branding must remain legible at phone widths instead of collapsing to a single letter or disappearing.
- Critical touch targets should meet the 48dp target wherever practical.
- Modal focus must not escape to background calling controls.

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

Activity/history must not silently truncate business records.

## Android shell contract

- WebView JavaScript and DOM storage enabled only as required by the local app.
- File URLs may load local packaged assets, but universal file-URL access remains disabled.
- Mixed content remains disabled.
- Safe Browsing remains enabled.
- External `tel:`, `mailto:`, `http:`, and `https:` intents are handed to Android appropriately.
- File import uses Android's document picker.
- Text/HTML/CSV export uses Android's create-document flow.
- Hardware back must not strand the user on SPA pages; app-level navigation must provide deterministic escape paths.
- The release icon/monoglyph remains locked to the approved asset and certificate identity.

## Torture Lab expectations

Every serious build should be subjected to:

- minimum supported Android API and current target API emulator runs
- real packaged WebView instrumentation
- persistence/reload regression fixtures
- deterministic queue/callback/appointment contract tests
- process death/relaunch
- random Monkey input and logcat crash/ANR scanning
- multiple viewport sizes/densities
- large font scaling
- screenshots of Hopper, menu, All Leads, Schedule, Reports, callback modal, appointment modal, No English modal, and long-content states
- package payload checks proving the tested HTML is the same HTML packaged into release
- static sentinels for data-loss, unsafe export, executable imported identifiers, duplicate IDs, and fixed-dock regressions

The Torture Lab is intentionally allowed to be red while known blocking defects remain. A green result should mean something.
