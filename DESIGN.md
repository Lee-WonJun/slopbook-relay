# Slopbook Design System

Status: `APPROVED`
Version: `1.6`
Approved: `2026-07-21 KST`
Normative detail: [`docs/design/`](docs/design/)

This document is the visual and interaction source of truth for Slopbook. Product rules remain normative in `docs/PRODUCT.md`, PRDs, and specs. If a screen, prototype, or implementation disagrees with this file, this file wins for presentation and `docs/DECISIONS.md` wins for product behavior.

## Product context

- **What this is:** a consumer social network where a human supplies intent and a selected persona supplies the public voice.
- **Who it is for:** people who want to play with several public identities without exposing their Google identity, plus visitors who need a lightweight local persona to join a comment thread.
- **Project type:** responsive social web application with public browsing, authenticated persona actions, anonymous comments, direct messages, and moderation tools.
- **Core trust rule:** generated expression may change. Meaning, target, facts, and claims may not.

## Brand

- Product name: `Slopbook`
- Official slogan: `Character. Beyond Anonymity.`
- Public abbreviation: none
- Supporting line: `Your intent. Their voice.` It belongs to launch artwork and long-form brand material, not routine application chrome.

The full English slogan appears at the public entry, first-persona onboarding, README, Devpost, demo opening and closing, and social artwork. It may wrap after `Character.` but may never be shortened to initials or translated.

## Direction: Paper X

Paper X combines the scan speed and density of an X-style social feed with warm paper surfaces and persona-first identity play.

- Strict rails, compact metadata, and continuous feed rows make the product feel like a real social network.
- Warm neutral surfaces prevent the interface from becoming a generic black-and-white clone.
- Persona Sigils and mixed persona chips carry the playful identity layer.
- Decoration is intentional and sparse. No tape, random card rotation, gradient hero, card soup, or soft-shadow elevation.
- The design should feel authored, slightly strange, and immediately usable.

### Safe choices

- Familiar three-column desktop shell and single-column mobile feed.
- Standard social actions, predictable navigation, and continuous rows.
- High contrast, visible focus, and explicit loading or failure states.

### Deliberate risks

- A profile asks `What are you?` and gives `Woman`, `Ghost`, and `Helicopter` equal visual weight.
- Warm paper and one direct sans-serif family appear inside an information-dense social product.
- Persona Sigils make each current public persona legible as a distinct visual specimen instead of reducing identity to initials or a random photo.

## Typography

- **Brand display:** self-hosted Noto Sans, 760/800 with tight tracking. The wordmark is distinguished by weight and spacing, not a decorative display serif.
- **UI, content, labels:** self-hosted Noto Sans, 400/500/600/700.
- **Metadata and numeric data:** Noto Sans with `font-variant-numeric: tabular-nums`.
- **Fallbacks:** `Noto Sans CJK`, then system sans-serif for every surface.
- **No remote font dependency in production.** The static design prototype may use local fallbacks.

| Token | Size / line-height | Use |
|---|---|---|
| `display` | 48/52 desktop, 36/40 mobile | brand slogan |
| `title-1` | 28/34 | page or profile title |
| `title-2` | 22/28 | modal and section title |
| `body` | 16/24 | posts and long copy |
| `ui` | 15/20 | navigation and controls |
| `meta` | 13/18 | handle, time, counts |
| `micro` | 12/16 | badges and helper text |

## Color

The theme defaults to the operating system. A user override stores `system`, `light`, or `dark` locally and does not affect persona output language or content.

| Role | Light | Dark |
|---|---|---|
| canvas | `#F3EBDD` | `#12110F` |
| surface | `#FFFDF7` | `#1B1916` |
| raised | `#FFFFFF` | `#24211D` |
| text | `#1E1C19` | `#F3EBDD` |
| muted | `#6D655C` | `#B9B0A3` |
| border | `#D3C6B4` | `#3B3630` |
| primary | `#2F46CC` | `#7890FF` |
| primary fill | `#3D5AFE` | `#7890FF` |
| coral | `#C94238` | `#FF8A7F` |
| yellow | `#8A6A00` | `#F6DE6C` |
| mint | `#287755` | `#9BE0C1` |

Accent colors identify state or persona, not decoration. Text uses the accessible text variant rather than bright decorative fills.

## Spacing, shape, and borders

- Base unit: 4px.
- Scale: `2, 4, 8, 12, 16, 20, 24, 32, 48, 64`.
- Feed row padding: 16px desktop, 12px mobile.
- Feed rows and navigation rails: radius 0.
- Inputs and small controls: 8px.
- Sheets and modals: 16px.
- Pills: tabs, filters, badges, and persona choices only.
- Borders: 1px solid by default, 2px for selected or focus emphasis.
- Elevation: tonal surface and border. No soft box shadows.

## Responsive shell

| Viewport | Shell |
|---|---|
| `>=1280px` | 272px identity rail / 680px feed or up to 880px editor / optional contextual rail only when backed by live data |
| `1024-1279px` | 88px compact identity rail / fluid center, no default right rail |
| `768-1023px` | 72px compact identity rail / fluid center |
| `<768px` | single column / 56px bottom navigation |

- Messages use the left rail on desktop and a top-right envelope on mobile.
- The desktop identity rail shows a `Your personas` roster with display name, handle, compact public-persona summary, active state, and `n / 5` capacity. Selection is one click and never cycles invisibly.
- The feed never becomes wider than 680px. Character editing may use up to 880px because it is a form, not a feed row.
- The product does not reserve a right rail for fixture content. A contextual rail is rendered only when a real API result or actionable system state exists.
- At 320 CSS px and 200% zoom, primary actions remain visible without horizontal page scrolling.

## Identity and avatars

- Default and fallback avatar: deterministic, theme-invariant Persona Sigil SVG derived from the normalized display name and complete `PersonaPublicV1`.
- Avatar sizes: 40px feed, 48px thread root, 112px profile header.
- Uploaded avatars: square crop, circular display, 128/256/512 WebP variants.
- Removing an upload, an invalid image, or a failed image returns to the Persona Sigil for the current public persona.
- The Sigil is an Oort-cloud-like particle field: a small central anchor, a deliberate inner void, and many dots radiating into denser outer bands. Hash-derived background/particle palette, cloud topology, inner-void radius, particle count, density curve, circle/ellipse ratio, tilt, cluster count, particle-size distribution, and color falloff make the field—not color alone—different between personas. Background and particles are theme-invariant. Connecting spokes, flower petals, and sunburst lines are forbidden.
- Editing the display name or any public persona field changes the Sigil; editing owner-only behavior, handle, locale, nationality, upload state, or account data does not.
- Runtime surfaces use cached image assets with deterministic size LOD. They never rebuild hundreds or thousands of particle DOM nodes per avatar; thousands of particles are reserved for 256px+ expansion/brand output.
- Guest personas use the same public-persona-derived Sigil rule. They are stored only in the current browser before posting, have no handle or public profile route, and never display the human-owned marker.
- Authenticated personas show a compact Slopbook person mark beside the display name: a filled head, open shoulder arc, and three quiet identity dots drawn as a platform-independent SVG. Its accessible name is `Google 로그인 인간 소유`.

The Persona Sigil construction, canonical seed, and upload behavior are specified in [`FOUNDATIONS.md`](docs/design/FOUNDATIONS.md).

## Character editor

Character creation and editing use one continuous page. There are no named steps, progress indicators, starter decks, pins, group rerolls, or review gate.

- `Random` fills or replaces the complete local draft, including a combinatorial display name, public persona, and required owner-only voice behavior. It never calls AI or the network.
- Every generated value remains directly editable in place. Required fields and optional details are grouped by meaning, not by a funnel stage.
- Handle, output language, nationality, and uploaded image are not changed by subsequent random actions after the user edits them.
- The Persona Sigil and compact public preview update immediately from current public fields.
- Saving is one explicit action. An account may have at most five active owned personas; the editor and server both expose the remaining capacity.
- The final editor section previews the exact first greeting that `Create persona` publishes once. Editing any field marks it stale and disables creation until refresh succeeds; publishing failure retains the created persona, preview text, and an idempotent retry.
- A signed-out visitor opens the guest variant at the same `/characters` route, not inside each thread. Save commits one browser-local guest; comment composers automatically reuse it and show only a compact identity row or setup CTA. Guest mode omits handle, account-only settings, profile preview, and profile links.

Public persona data and owner-only behavior data are different contracts. See [`PERSONA_SYSTEM.md`](docs/design/PERSONA_SYSTEM.md).

## Feed and actions

- Feed items form one continuous surface separated by 1px dividers. Do not wrap each item in a card.
- The action row contains comment, persona-selected reaction, repost, and share in four stable slots. Repost is an explicit persona action and never invokes AI.
- A resurfaced feed row shows a compact custom-SVG repost attribution above the author line without wrapping the canonical post in another card.
- Reaction controls use Slopbook's own six line icons rather than operating-system emoji. The idle action shows one neutral decision mark; the summary above it renders only reaction types whose visible count is greater than zero.
- The human-owned marker is adjacent to the display name, never used as a floating status badge.
- Profile tabs are `Posts`, `Replies`, and `Media`.
- Compose surfaces show the active avatar, handle, output language, and a short persona summary.
- Do not repeat product-mechanism narration in page subtitles, empty states, rails, or composers. Explain privacy only where consent, failure recovery, or destructive action requires it.

## Motion

- Approach: minimal and functional.
- Micro transition: 120ms.
- Standard transition: 180ms.
- Sheet or scene transition: 240ms.
- Enter: `cubic-bezier(.2,.8,.2,1)`.
- Exit: `cubic-bezier(.4,0,1,1)`.
- Persona Sigil particles may breathe radially by at most 1px while idle. A generated draft may use one 160ms outward density pulse; particles never spin as a group.
- `prefers-reduced-motion` removes drift, pulse, slide, and animated counters. State changes remain immediate and textual.

## State and accessibility rules

- Every async surface defines initial loading, refreshing, empty, validation error, permission error, retryable error, policy error, offline, success, and degraded states.
- All controls have visible labels or accessible names, keyboard operation, 44px mobile hit targets, and visible `:focus-visible` treatment.
- Text contrast targets WCAG 2.2 AA: 4.5:1 normal text and 3:1 large text or non-text controls.
- Every bounded list, choice group, media picker, and text field shows its live `current / maximum` count beside the label. Choice groups also expose selected state. Randomization changes are announced once through a polite live region.
- Theme, viewport, locale, content length, empty/error state, and reduced motion are review dimensions, not afterthoughts.

## Prototype authority

[`docs/design/prototype/index.html`](docs/design/prototype/index.html) remains a revision 1.3 non-runtime visual archive for the nine-scene system and unified editor. The original 36-image matrix remains revision 1.1 historical evidence. This file and the linked revision 1.4 design documents are normative; current application runtime evidence, not the archived prototype, proves the saved-guest and first-post states.

## Governance

- Read this document before making visual or UI decisions.
- A change to slogan, public/private persona boundaries, layout breakpoints, avatar identity, or accessibility targets requires a new ADR and human approval.
- Component-level changes update the relevant design module and screenshot evidence in the same change.
- G1 is approved only when [`APPROVAL.md`](docs/design/APPROVAL.md) says `approved` and links its evidence.

## Decisions log

| Date | Decision | Rationale |
|---|---|---|
| 2026-07-16 | Adopt Paper X | Keeps SNS scanning familiar while giving Slopbook a warm, persona-specific identity. |
| 2026-07-16 | Use the full English slogan | The phrase is the public brand. Initials were only a rhyme and are not a usable slogan. |
| 2026-07-16 | Split public persona and owner-only behavior | Public profiles stay legible while detailed voice rules remain editable without leaking into snapshots. |
| 2026-07-19 | Persona repost and account-fair Hot | Reposts create visible distribution while unique external account counts prevent a five-persona account from multiplying Hot influence. |
| 2026-07-16 | Replace Orbit with an Oort-field Persona Sigil | The current public name and persona now produce a deterministic cloud of radiating particles whose density, silhouette, and color vary across personas. |
| 2026-07-16 | Replace starter decks with one full-persona randomizer | Character creation should feel like discovering a surprising complete profile, then editing it, rather than choosing a preset archetype. |
| 2026-07-16 | Collapse persona creation into one editor | Random generation is an optional shortcut inside an editable form, not a three-act onboarding story. |
| 2026-07-16 | Put the owned-persona roster in the left rail | Multiple personas must be visible and directly selectable; hidden cycling is not sufficient. |
| 2026-07-16 | Let visitors create local guest personas for comments | Comment participation should not require an account or imply a public profile. |
| 2026-07-16 | Remove fixture rails and explanatory product narration | The application should read as a working social product, not a concept demo generated around its own premise. |
| 2026-07-17 | Group catalog v2 options and expose every bounded count | Legacy Slopbook's concrete social-native range returns inside the structured public/private contract, while visible limits make the editor predictable. |
| 2026-07-17 | Preselect one guest and preview the first persona post | A browser-level identity should not be rebuilt per thread, and persona creation should reveal the exact greeting that becomes public. |
