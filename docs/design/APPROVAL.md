# G1 Design Approval

Status: `approved`
Decision date: `2026-07-21 KST`
Human direction: approved for document implementation in the current task
Revision: `1.6` — user-facing persona terminology synchronized; legacy `character` identifiers remain internal

## Approved direction

- Official slogan: `Character. Beyond Anonymity.`
- Visual system: Paper X
- Responsive SNS shell: identity roster plus working surface on desktop and single-column mobile
- Avatar: deterministic Persona Sigil from normalized display name plus complete public persona, rendered as an Oort-like radiating particle field with optional upload
- Persona creation: one-page directly editable persona editor with one local `Random` fill action and no steps, pins, or group rerolls
- Multi-persona: account roster shows up to five owned personas with direct active selection
- Guest participation: one signed-out local guest selected on `/characters` and reused by all comments, with no public profile or private-behavior snapshot
- First utterance: owned creation previews and publishes the exact greeting once, with stale, retry, and idempotency states
- Feed actions: comment, persona-selected reaction, explicit persona repost, and share use platform-independent icons and stable async feedback
- Exposure: Latest resurfaces one canonical post at immutable repost time; Hot collapses sibling personas to distinct external accounts
- Product chrome: no fixture right rail or repeated mechanism narration; public policy routes are linked from the footer
- Contract: separate `PersonaPublicV1` and owner-only `PersonaBehaviorV1`
- Theme: system-default light and dark
- Prototype authority: revision 1.1 screenshot matrix and revision 1.3 persona scene are historical; revision 1.5 runtime behavior is proven by current local tests and production evidence remains tracked by SLOP-045. Revision 1.6 changes terminology, not layout or behavior.

## Gate checklist

- [x] Root `DESIGN.md` is normative.
- [x] Foundation tokens and responsive layout are specified.
- [x] Persona UX and public/private contracts are specified.
- [x] Components and async states are specified.
- [x] Nine scenes are specified.
- [x] English, Korean, Simplified Chinese, Japanese, and accessibility rules are specified.
- [x] Revision 1.1 static 36-image matrix and revision 1.3 prototype character scene remain indexed as historical evidence; revision 1.4 uses live runtime evidence.
- [ ] Revision 1.5 production desktop/mobile light/dark, 320px, 200% zoom, keyboard, and reduced-motion evidence is required by SLOP-045 before release.
- [x] Brand, PRD, spec, traceability, roadmap, and ticket documents are synchronized.
- [x] Documentation consistency checks pass.
- [x] Persona Sigil canonical hash test vector and visual-genome mapping are specified.
- [x] Persona Sigil delivery uses cached, immutable LOD image assets with zero per-frame particle JavaScript and explicit feed/profile byte budgets.
- [x] Starter decks, steps, pins, and group rerolls are removed from normative revision 1.2 UX; Random remains local, editable, and no-network.
- [x] The product owner explicitly approved the five-character roster, unified editor, guest persona, copy removal, policy footer, grouped legacy-informed options, and visible limit counters.
- [x] The product owner explicitly approved one preselected guest across comment threads and an exact greeting preview that publishes once on persona creation.
- [x] The product owner explicitly requested repost and ranking/exposure behavior; ADR-008 records the no-duplicate and account-fair decision.
- [x] DEC-035 makes `persona` the user-facing product term while preserving legacy implementation identifiers.

## Evidence

- [`DESIGN.md`](../../DESIGN.md)
- [`FOUNDATIONS.md`](FOUNDATIONS.md)
- [`PERSONA_SYSTEM.md`](PERSONA_SYSTEM.md)
- [`COMPONENTS_AND_STATES.md`](COMPONENTS_AND_STATES.md)
- [`SCREENS.md`](SCREENS.md)
- [`COPY_I18N_A11Y.md`](COPY_I18N_A11Y.md)
- [`prototype/index.html`](prototype/index.html) — revision 1.1 archive
- [`evidence/README.md`](evidence/README.md) — revision 1.1 archive
- [`evidence/VALIDATION.md`](evidence/VALIDATION.md) — revision 1.1 archive
- [`ADR-003`](../adr/ADR-003-persona-sigil-and-random-persona.md)
- [`ADR-005`](../adr/ADR-005-character-roster-unified-editor-and-guest-persona.md)
- [`ADR-006`](../adr/ADR-006-product-chrome-and-public-trust-surfaces.md)
- [`ADR-007`](../adr/ADR-007-persistent-guest-selection-and-previewed-first-post.md)
- [`ADR-008`](../adr/ADR-008-persona-reposts-and-account-fair-feed-ranking.md)

## Final gate

G1 is approved for application implementation against revision 1.6. SLOP-043, SLOP-044, SLOP-046, and SLOP-047 provide local API, privacy, accessibility, ranking, and interaction evidence. SLOP-045 still owns production deployment evidence. A later change to the five-persona limit, guest profile boundary, single-page editor, first-post authorization, or account-fair ranking requires another ADR and approval revision.
