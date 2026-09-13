# Lead Hopper Phase 0 Consolidation Record

Status: ACTIVE

Active engineering branch: `stabilization/1.7.7-bridge-completion`

Purpose: finish the 1.x migration bridge without redesigning the product, preserve a known-good Lead Hopper reference, and produce trustworthy evidence that existing user data can survive the transition to native Android 2.0.

## Governing principle

**Preservation by default; change by evidence.**

Phase 0 is not a product rewrite. The existing Lead Hopper is already a useful, fast, local-first calling tool. Phase 0 repairs data-safety, correctness, migration, backup/restore, security, release, and real-world usability blockers while avoiding cosmetic churn and unnecessary WebView redesign work that native 2.0 will replace.

Nothing is permanently immutable, but any intentional behavior change away from the known working product must have an explicit reason and test evidence.

## Authority order

When sources conflict, use this order:

1. Latest explicit user decision.
2. `Lead_Hopper_2.0_Native_Build_Specification.md` where compatible with Phase 0.
3. `docs/LEAD_HOPPER_TORTURE_CONTRACT.md` after reconciliation to the latest decisions.
4. Compatible V12 / Android PRD rules.
5. Historical implementations only as behavioral evidence.

A bug does not become a requirement because old code happened to contain it.

## Branch roles

- `main`: preserved known-usable 1.7.6-era baseline / golden-reference lineage. Do not use it as an experimental integration branch.
- `stabilization/1.7.7-data-survival`: historical checkpoint only.
- `stabilization/1.7.7-bridge-completion`: the only active Phase 0 engineering line.
- Native 2.0 branch: do not start until Phase 0 exit criteria are satisfied and the bridge checkpoint is frozen.

## Golden reference policy

The current working 1.x application must remain preserved even after native 2.0 exists. Its role is behavioral oracle, recovery reference, documentation artifact, and comparison target.

Preserve at minimum:

- exact source commit
- exact signed APK used as reference
- APK SHA-256
- signing certificate fingerprint / signing continuity record
- exact packaged HTML/JS payload
- approved full-color icon, monoglyph, and logo assets
- screenshots/workflow evidence for representative states
- a short behavioral record of the normal calling workflow

Native 2.0 is not allowed to drift casually because a different architecture makes another design easier. Extra taps, unnecessary screens, slower interaction, lost information density, avoidable abstraction, and new failure modes are regressions unless they buy a concrete product benefit.

## Settled product decisions

These are not open Phase 0 design questions:

- Call Now occupies the dominant full row.
- Row 2 is Not Interested left / No Answer right at an exact 50/50 split.
- Row 3 is Callback left / Quote Appointment right at an exact 50/50 split.
- The four common-action buttons use the same height as Call Now.
- Callback advances only after a successful save. Cancel, invalid input, or failed persistence must not advance or partially apply.
- Undo restores the prior business state but does **not** erase audit history. The original event remains identifiable as reversed and a reversal/Undo event is appended.
- One normalized phone number gets one cold-Hopper opportunity while every individual person and their contact information remain separate records.
- Shared-phone `+N` UI is reference-only and cannot switch the active person/context.
- DNC is phone-wide.
- Wrong Number is person + phone specific.
- Person-specific notes, history, email, address, callbacks, and appointments stay attached to that person.
- Any custom action that sets a future eligibility date makes the lead unavailable to the ordinary Hopper until that date.
- Replace List replaces the active calling roster but archives people omitted from the replacement instead of destroying their history. Existing scheduled items for removed people must be surfaced for resolution rather than silently orphaned or deleted.
- Full-color Lead Hopper logo is the phone-header brand element.
- Approved icon and monoglyph artwork remain locked; approved full-color launcher scaling must match the monoglyph footprint.
- Native 2.0 is an architecture reset, not a product reset, and does not use the production WebView architecture.

## Phase 0 scope boundary

A defect is a Phase 0 blocker when it can materially affect:

- data survival or data integrity
- correct person/phone attribution
- queue or eligibility correctness
- disposition transaction correctness
- suppression correctness
- backup/restore correctness
- migration correctness
- imported-data trust/security
- required navigation or live-use accessibility
- package/signing/release continuity
- behavior explicitly protected by the Torture Contract

Purely cosmetic or legacy-WebView annoyances may remain when they do not violate those conditions and native 2.0 will replace them. Do not spend Phase 0 polishing an architecture we have already chosen to retire.

## Current completion matrix

| Area | Current state | Evidence / issue | Phase 0 action |
| --- | --- | --- | --- |
| Core calling workflow | Mostly proven | Existing instrumentation covers call gate, No Answer, callbacks, appointment spacing, Previous, Undo, geometry | Preserve; fix only demonstrated regressions |
| Callback advance | Settled | Must advance only after successful persistence | Keep test-protected |
| Shared phone identity | Contract defined | Reference-only `+N`, phone-wide DNC, person+phone Wrong Number | Reconcile all stale tests/docs and prove scenarios |
| Custom future snooze | Decision settled | Any future eligibility timestamp must exclude from ordinary Hopper | Implement/prove generic rule |
| Replace List | Decision settled, implementation requires audit | Removed people must archive; linked schedule/history cannot be orphaned | Implement/prove archive + resolution behavior |
| Startup hydration | Substantive implementation present | V19 hydration guard blocks pre-load writes | Keep fail-closed and prove process/reload behavior |
| Native migration snapshot | Substantive implementation present | `MigrationSnapshotStore` uses AtomicFile, generations, hash integrity, pending/current journal and history | Complete end-to-end bridge proof |
| Persistence instrumentation | Fix in progress | Previous teardown incorrectly checked `window.state` even though `state` is a top-level lexical binding; teardown now saves and verifies native recovery bytes equal local bytes | Re-run API 26/35 gates |
| Legacy localStorage adoption | Test added | First bridge launch must accept existing local-only 1.x bytes when no native journal exists | Re-run gate |
| Backup/restore | Substantive implementation present | Versioned full backup + strict restore exists; destructive wipe/restore equality still needs full acceptance proof | Add/complete destructive round trip |
| Native export | Substantive implementation present | Android create-document bridge has saved/failed/cancelled/busy results and disk-backed pending export | Complete failure/concurrency acceptance |
| Corrupt state | Fail-closed path exists | Recovery UI prevents continued mutation | Prove corrupt journal/local state scenarios |
| API 26 compatibility | Previously red | Earlier run showed persistence-test contamination/readiness timeouts after the first failures; Monkey was clean | Re-run after persistence harness correction |
| API 35 compatibility | Previously red | Four false persistence teardown failures plus Monkey found an actual pre-bootstrap `uiAppointment` ReferenceError | Startup actions now initially disabled; re-run |
| Monkey/random abuse | API 26 clean, API 35 previously red | API 35 log showed `Uncaught ReferenceError: uiAppointment is not defined` from a tap before handler availability | Bootstrap-lock static action controls; require clean rerun |
| Visual sweeps | Green in latest inspected API 35 run | Compact/modern/large-font sweeps completed | Preserve; do not use as excuse for cosmetic churn |
| Package/static contract | Green in latest inspected run | Tested/release payload equality and static sentinels passed | Must stay green |
| Launcher parity | Approved change ported | Bridge now carries the approved 64dp full-color foreground geometry matching monoglyph | Preserve |
| Golden reference archive | Not yet fully frozen | Behavioral reference exists but archival record/artifact set still needs formal freeze | Create final golden record before Phase 0 exit |
| Same-signer populated upgrade | Not yet complete | This is the real migration acceptance test | Required before bridge freeze |
| Signed 1.7.7 bridge checkpoint | Not yet cut | Depends on complete green evidence | Final Phase 0 output |

## Evidence ladder

Work down this ladder in order. Do not declare Phase 0 complete from a successful build alone.

1. Asset preparation / static hostile sentinels.
2. Debug, release, and Android-test package builds.
3. Proof that tested WebView bytes equal release WebView bytes.
4. Snapshot-store unit/instrumentation fault cases.
5. Real packaged WebView instrumentation.
6. API 26 emulator acceptance.
7. API 35 emulator acceptance.
8. Reload, close/reopen, background, process-kill, corrupt-state, and failed-write scenarios.
9. Backup -> wipe -> restore -> structural equality.
10. Merge/Replace, suppression, shared-phone, callback FIFO, Undo/audit, custom snooze, reports/navigation.
11. Monkey/random abuse and crash/ANR/uncaught-JS scan.
12. Real populated same-signer 1.x -> 1.7.7 Bridge upgrade.
13. Real-device calling session sanity check.
14. Signed release verification, checksum, certificate identity, and artifact archive.

## Phase 0 exit criteria

Phase 0 is complete only when:

- the canonical completion matrix has no unresolved blocker
- package/static contract is green
- API 26 and API 35 acceptance gates are green without weakening valid tests
- backup/restore survives destructive round-trip proof
- migration snapshot survives reload/process/failure cases
- all latest identity/suppression/Undo/Replace/custom-snooze decisions are implemented and tested
- a populated same-signer upgrade succeeds without data loss or unexplained relationship changes
- signing identity and package continuity are verified
- the golden reference is preserved
- a clean signed 1.7.7 Bridge checkpoint is archived with evidence

Only then create/start the native 2.0 implementation line.
