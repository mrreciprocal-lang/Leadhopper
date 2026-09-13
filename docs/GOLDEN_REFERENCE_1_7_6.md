# Lead Hopper 1.7.6 Golden Reference

Status: PRESERVED REFERENCE — NOT AN ACTIVE DEVELOPMENT LINE

This record exists because the 1.x Lead Hopper is already a useful, fast, lightweight application whose normal calling workflow is close to the intended product. Native Android 2.0 must improve the foundations without casually drifting away from what already works.

## Immutable source reference

- Archive branch: `archive/golden-1.7.6-reference`
- Exact source commit: `ea9294eb9655e87fb7855d80491749e418a98aac`
- Commit message: `Match full-color launcher icon scale to monoglyph`
- Package/application ID: `com.kyle.leadhopper`
- Version code: `25`
- Version name: `1.7.6`
- Minimum Android API: `26`
- Target/compile Android API: `35`

The archive branch was created directly from that exact commit. Do not move it forward to follow later bridge or native work. If a later golden revision is intentionally chosen, create a separate archival reference rather than rewriting this one.

## What this reference means

This is the behavioral and visual oracle for the end of the 1.7.6 prototype era. It is not certified bug-free. The torture work already demonstrated that its surrounding persistence/backup/failure architecture needed hardening. What is being preserved is the known working product character and the exact source state from which the Phase 0 bridge diverged.

Use it to answer questions such as:

- How many interactions did an ordinary call/disposition require?
- How dense and responsive did the Hopper feel?
- What information was visible on the lead card?
- How did the primary action dock look and behave?
- What did the approved full-color/themed launcher treatment look like?
- Did a proposed native workflow add avoidable taps, screens, latency, or abstraction?

Native 2.0 does **not** have to preserve WebView implementation details or known defects. It does have to justify intentional behavioral departures.

## Current source-build evidence

GitHub Actions build run `34772234395` completed successfully for the exact golden commit and produced:

- `LeadHopper-1.7.6-release-unsigned` — artifact ID `10322122848`, workflow artifact digest `sha256:64624d11d42b3c7cb6f708bbe8b60f3b55f72b6edc03f387105c3483c3192118`
- `LeadHopper-signing-tool` — artifact ID `10322247633`, workflow artifact digest `sha256:71b34219b12af6d977bf98ffc8b9f44c40f84ece22f09789939e45d5c3390ea0`

Those workflow artifacts are supporting evidence, not the final permanent golden APK archive; GitHub Actions artifacts expire. Before Phase 0 exit, preserve a permanent signed golden APK/checksum/signing record outside the expiring Actions artifact pool or in a durable release/archive location.

## Artwork lock

The exact source reference includes the approved monoglyph and the final full-color launcher foreground scaling change. The bridge has separately carried that approved 64dp parity change forward. Do not regenerate or redesign these assets as part of Phase 0.

## Known limitations of the golden reference

Do not mistake preservation for certification. The golden 1.7.6 lineage still had known or suspected failure-edge problems including persistence boot ordering, backup/export semantics, pathological import/input cases, some legacy WebView containment issues, and incomplete failure-mode proof. Those are precisely why 1.7.7 Bridge exists.

The rule is therefore:

**Golden 1.7.6 defines what we are trying not to lose. 1.7.7 Bridge defines how we safely carry its data forward. Native 2.0 replaces the machinery without casually replacing the product.**

## Phase 0 remaining archival tasks

Before declaring Phase 0 complete:

1. Produce/preserve the final signed golden APK and record its file SHA-256.
2. Verify and record the release certificate SHA-256 from that exact signed APK.
3. Preserve representative golden screenshots/workflow evidence.
4. Preserve the exact production WebView payload used by that signed APK or prove its equality to the archived source payload.
5. Keep this archive branch and its source commit intact after native 2.0 ships.
