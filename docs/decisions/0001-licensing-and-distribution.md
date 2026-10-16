# 0001 - HMX Docs licensing and distribution model

- **Status:** OPEN - awaiting the repository owner's decision
- **Date opened:** 2026-10-16
- **Blocks:** any distribution of HMX Docs builds; Phases 9-10 of the roadmap
- **Does not block:** Phase 1 build/CI work, local or CI compilation, unsigned
  APK artifacts (not distributed), and read-only research

## Context

HMX Docs is built on Kern, which is licensed **AGPL-3.0** (`LICENSE`,
`README.md`). The codebase also compiles **GPL-3.0** code: the Qyra `kern-bridge`
Rust crate, built into `libkern_pdf.so` and shipped inside every APK that includes
the PDF tools (`src/pdf-bridge/README.md`, `src/pdf-bridge/QyraPdf.kt`).

Both licenses carry obligations that depend on how HMX Docs will be distributed.
The decision has not been made yet, and this record must not invent it.

## Facts that constrain the decision

1. **AGPL-3.0 is viral for derivative works.** A modified or extended Kern must be
   distributed under AGPL-3.0, with source offered to recipients. If HMX Docs ever
   includes a server or network component, AGPL §13 extends the source-offer
   obligation to users interacting with it over a network.
2. **GPL-3.0 inside AGPL-3.0 is compatible** (upward), but GPL-3.0 §6 requires
   shipping the corresponding source *and the build scripts* for `libkern_pdf.so`
   to whoever receives the binary.
3. **Copyright notices must be preserved.** Kern's `LICENSE`, `README.md`
   (Copyright © 2026 Sricharan Suresh), `res/raw/license.txt` and per-file headers
   identify the original authors. A rebrand to "HMX Docs" is permitted for a
   modified AGPL work only if those notices stay and the changes are stated.
4. **Rebranding is a trademark question, not a copyright one.** Renaming the app
   and replacing its icons is allowed by the license; this may not imply
   endorsement by the original author.
5. **Google Play adds policy constraints,** separate from copyright: the
   `MANAGE_EXTERNAL_STORAGE` permission (all-files access) requires an approved
   use case and a Play declaration.
6. **No third-party notice bundle exists yet** for POI, OpenCSV, jsoup, XMLBeans
   and the commons transitives (only the bundled fonts carry license files, in
   `licenses/fonts/`).

## Options

### Option A - AGPL-3.0-compatible, source-available HMX Docs (recommended)

Keep the AGPL-3.0 license. Publish the source of the HMX Docs fork. Ship GPL-3.0
notices for the Qyra bridge and add a third-party notice page. This is the option
the current codebase already supports with no relicensing work.

- Consequences: anyone who receives the app (or interacts with a hosted version)
  is entitled to the corresponding source; monetization cannot rely on keeping the
  code closed. Distribution on Play is possible, subject to the all-files
  declaration.
- Work required: notice bundle, "changes made" statement, Qyra source offer, and
  the Play declaration text.

### Option B - proprietary / closed-source HMX Docs

If HMX Docs must ship without source (or with an incompatible license), this
codebase **cannot** be its base: AGPL-3.0 forbids adding such restrictions, and
the GPL-3.0 native bridge cannot be combined into a proprietary binary. Choosing B
means restarting from a permissively licensed base (or licensing a commercial
office/docx SDK), and re-planning the roadmap from Phase 0.

- Consequences: none of the Kern code, tests, or the roadmap's sunk analysis can
  be reused in the proprietary product.
- Note: the AGPL-3.0 base may still be a lawful *research* base; the constraint
  applies to distributing a derivative.

## Decision

*Not decided.* The repository owner must choose A or B, ideally with legal review
before any public distribution. Until then:

- Phase 1 may continue: building in CI and storing artifacts is not distribution.
- No public release, no store listing, and no claim of legal clearance may be made.

## Consequences of not deciding

Phase 1 does not depend on this. Phases 9 and 10 (hardening, productization,
release candidate) do: the notice bundle, About screen and Play declarations all
derive from it. Deciding late means rework, not a dead end - except in Option B,
where the rework is the whole project.

## Not legal advice

This record summarizes license texts and project facts. It is not a legal opinion;
a qualified lawyer should review the chosen option before distribution.
