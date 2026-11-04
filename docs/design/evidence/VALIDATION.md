# G1 Design Validation

Status: `PASS`
Reviewed: `2026-07-17 KST`
Scope: revision 1.1 screenshot archive plus revision 1.3 character-scene spot check; no application runtime

## Rendered browser checks

The prototype was served locally from `docs/design/prototype/` and inspected in Chromium. The 36-file matrix remains the revision 1.1 historical archive. The revised character editor was separately spot-checked at desktop and mobile sizes in both themes. No application server, API, database, authentication provider, model provider, or deployment environment was used.

| Gate | Coverage | Result |
|---|---|---|
| Screenshot matrix | 9 scenes × desktop/mobile × light/dark | PASS, 36 files |
| Screenshot paint integrity | RGB pixel-stat scan after visible-scene-only renderer change | PASS, 36/36 non-blank and non-uniform |
| Deterministic scene query | every `scene` and `theme` pair | PASS, correct body state and `h1` |
| Matrix horizontal overflow | `1280x900`, `375x812`, both themes | PASS, 36/36 `scrollWidth === innerWidth` |
| Tablet reflow | all scenes at `768x1024`, both themes | PASS, 18/18 |
| Narrow reflow | all scenes at `320x812`, both themes | PASS, 18/18 |
| 200% reflow equivalent | all scenes at `640x900`, representing a 1280px viewport at 200% | PASS, 18/18 |
| Console | final nine-scene traversal | PASS, no page console errors |
| Network | local HTML, CSS, JS only | PASS, no failed requests |
| Public/private presentation | public scene scan and owner settings scene | PASS, behavior controls appear only in owner settings |
| Persona randomizer | keyboard-triggered full draft fill with network buffer cleared | PASS, generated name/profile/Sigil changed atomically, bounded groups retained visible `current / maximum` counts, network requests 0 |
| Persona Sigil renderer | five sample genomes declared across 42 prototype avatars; visible-scene-only rendering | PASS, deterministic Oort-like circle/ellipse particle fields, seed-derived internal background, no connecting lines |

The tablet admin table and 320px message list initially overflowed. The table columns/filter and conversation min-width were corrected, then both failing sizes were rerun in light and dark with zero overflow.

## Keyboard and focus

- Tab reaches the skip link, scene selector, and both theme buttons.
- The focused skip link, selector, theme control, and Persona shuffle control compute a `2px` focus-colored outline with `2px` offset.
- `End` plus `Enter` on the scene selector changed the active scene to `admin` and exposed the `Report queue` heading.
- `Enter` on the dark theme button changed `data-theme` to `dark` and synchronized both `aria-pressed` values.
- Focus on `Randomize persona` computes a `2px` focus-colored outline with `2px` offset.
- `Enter` on `Random` generated `Velvet Comet`, replaced the complete editable draft, updated the public profile and 245-particle Sigil, and made zero network requests.
- The prototype contains no starter-deck, stepper, pin, group-reroll, or undo control.
- A final scene-by-scene audit found zero icon-only buttons without an accessible name.

## Persona Sigil particle field

- The reference renderer emits only SVG circles and groups: `line`, `path`, and `polygon` count is `0`, so there are no spokes, petals, sunbursts, or literal orbit paths.
- The five deterministic sample genomes render 170, 190, 205, 220, and 245 particles. Instances sharing a sample seed have byte-identical SVG markup.
- The sample set varies internal profile-image background, circle/ellipse aspect, tilt, inner void, radial density, cluster warp, particle size, and inner-to-outer color falloff.
- Rotor's profile-image background is `#152138` in both app themes. Its SVG hash is `7f943530` in light and dark, proving the service theme does not recolor the profile image.
- The normalized Persona Sigil test vector hashes to `02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d` with SHA-256.
- The prototype uses a deterministic local visualization of the documented genome; the future application renderer must use the normative SHA-256/canonicalization contract rather than the prototype's compact sample helper.
- Only the current review scene is materialized, limiting the inspected wizard scene to two Sigil SVGs instead of all 42 declared avatars. Runtime must go further and use cached image assets; prototype circles are not application guidance.

## Reduced motion

CSSOM inspection found the active rule `@media (prefers-reduced-motion: reduce)`. Inside it, scroll behavior is immediate and animation duration, iteration count, and transition duration are constrained to a single `1ms` state change. The prototype contains no information available only through motion.

## Locale overflow

At `320x812` and `768x1024`, the Persona heading, `What are you?` helper, and `Randomize persona` action were replaced in the live DOM with approved English, Korean, Simplified Chinese, and Japanese strings. All eight locale/viewport runs retained page width and control width without clipping or horizontal overflow. Catalog IDs remain language-neutral; localized labels and user-entered custom text follow [`../COPY_I18N_A11Y.md`](../COPY_I18N_A11Y.md).

## Contrast calculation

Ratios use relative luminance from the final token values.

| Pair | Ratio |
|---|---:|
| light text / surface | 16.71:1 |
| light muted / surface | 5.63:1 |
| light primary / surface | 7.20:1 |
| light coral / surface | 4.78:1 |
| light yellow / surface | 4.98:1 |
| light mint / surface | 5.35:1 |
| light on-primary / primary fill | 5.13:1 |
| dark text / surface | 14.82:1 |
| dark muted / surface | 8.19:1 |
| dark primary / surface | 6.04:1 |
| dark coral / surface | 7.68:1 |
| dark yellow / surface | 13.02:1 |
| dark mint / surface | 11.55:1 |
| dark on-primary / primary fill | 6.50:1 |

The first light-coral candidate measured 4.10:1 and was rejected. The final `#C94238` token measures 4.78:1 against the light surface.

## Document and contract checks

| Check | Result |
|---|---|
| JSON code blocks parse | PASS, 29 blocks |
| Persona examples | PASS, 6 public, 4 concrete behavior, 55 concrete `ChoiceRef`, 4 Persona Sigil descriptors |
| Cardinality, enum, and grapheme limits | PASS for every concrete persona example |
| Persona Sigil canonical vector | PASS, documented SHA-256 reproduced exactly |
| Persona Sigil performance contract | PASS, cached immutable `<img>` LODs, zero per-frame JS, bounded particles and byte budgets specified; 50-row runtime evidence assigned to SLOP-031 |
| Ticket IDs, dependencies, referenced requirements | PASS, 42 tickets, no missing dependency or cycle |
| Deprecated public slogan | PASS; initials remain only in ADR-001 historical/rejected context |
| Deprecated flat authenticated persona fields | PASS; remaining `personality`/`catchphrase` example is the separate anonymous-comment contract |
| Deprecated authenticated avatar/randomizer rules | PASS; old terms remain only in superseded/rejected historical context |
| Public snapshot boundary | PASS; public persona only, owner behavior excluded |
| Markdown local links | PASS, 149 targets |
| `git diff --check` | PASS |

## Approval boundary

This evidence approves the design package and static prototype for G1. It does not claim WCAG conformance, runtime correctness, API behavior, AI safety, or production privacy for the future application. Those require implementation-ticket tests and runtime evidence.
