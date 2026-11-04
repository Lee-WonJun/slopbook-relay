# Screen Specification

Status: `APPROVED`
Parent: [`DESIGN.md`](../../DESIGN.md)
Visual reference: [`prototype/index.html`](prototype/index.html)

The prototype groups the product into nine review scenes. A scene may contain adjacent states or a small state lab, but it must remain possible to inspect each required behavior at desktop, tablet, and mobile widths.

## Scene inventory

| ID | Prototype key | Scene | Primary proof |
|---|---|---|---|
| 01 | `public-feed` | Public Latest feed | unauthenticated reading without concept-demo narration |
| 02 | `signed-feed` | Signed-in feed shell | identity rail, full owned roster, compose entry |
| 03 | `persona-editor` | Unified character editor | one-page random fill, live Sigil, direct editing |
| 04 | `writing-states` | Post/comment/DM state lab | identity context, counters, processing and errors |
| 05 | `thread-trust` | Thread, reaction, guest persona, report/block | two-level thread and trust actions |
| 06 | `profile-settings` | Character profile, settings, avatar | public/private split and Persona Sigil fallback |
| 07 | `search-notifications` | Search and notifications utility scene | active-character notification context |
| 08 | `messages` | DM list and detail | transformed-only copy and responsive navigation |
| 09 | `admin` | Admin report queue | dense operational hierarchy and safe actions |

## 01. Public entry and feed

### Desktop

- Left rail shows logo and public navigation, with a sign-in action instead of character switcher.
- Center header says `Latest`.
- The feed begins with data, loading, or a compact resource-specific empty state. It does not insert a slogan panel or oversized character CTA.
- Without a saved guest persona, a compact onboarding strip offers a browser-local persona for comments. It does not claim that signed-out visitors can publish.
- There is no default right rail. Policy links live in the footer, not a promotional card.

### Mobile

- Bottom navigation shows public Feed and Search plus sign-in entry points.
- The first viewport is a working feed state, not an onboarding poster.

## 02. Signed-in feed shell

- Desktop proves the 272px identity rail and 680px feed at 1280px.
- The left rail shows every owned character, active state, compact summary, and `n / 5`; the compose identity repeats only at the writing boundary.
- Feed rows are continuous and separated by borders, with no outer cards.
- Action row contains comment, persona-selected reaction, repost, and share in four stable slots. A resurfaced row adds compact repost attribution above the author line.
- Mobile removes the right rail and exposes Messages in the top-right header.

## 03. Unified character editor

- The scene shows one page titled `New character` with no step labels or progress.
- A secondary `Random` action changes the complete local draft, including the display name.
- The live Persona Sigil demonstrates an Oort-like dot cloud with a central void, circular/elliptical silhouette, density bands, and varied particle scale.
- `What are you?` visibly mixes `Woman`, `Ghost`, `Robot`, `Weather`, and `Helicopter`.
- Every visible result is directly editable; no pin or group dice control exists.
- Optional details are collapsed with a completion count.
- Public fields and `Private voice settings` are separated inside the same form. Mobile preserves counts and makes save actions reachable without covering content.
- The final `First post` region demonstrates ready and stale states. Its exact preview is posted once by Create; editing any field disables Create until refresh succeeds.
- Signed-out `/characters` uses the same route shell for a browser-local guest editor, with explicit Save/Cancel and no profile affordance.

## 04. Writing state lab

The scene shows three compact examples:

1. Post compose in idle or transforming state.
2. Comment compose with a retryable error and preserved raw input.
3. DM compose with transformed-message privacy copy.

The scene explicitly demonstrates that no permanent educational banner is repeated in normal writing UI.

## 05. Thread and trust

- Root post, top-level comment, and flattened reply demonstrate two visible levels.
- `반응하기` shows pending and chosen reaction states with text plus Slopbook SVG. Only reaction types with a positive visible count appear in the received-reaction summary.
- The signed-out composer shows either a compact saved guest identity or a setup CTA. Edit opens `/characters`; the full guest form never appears inside the thread. The deletion-secret limitation remains beside comment submit.
- Report sheet and account-level block confirmation state the actual effect without exposing owner identity.

## 06. Profile and settings

- Profile header uses a 112px Persona Sigil and compact public persona card.
- Tabs are Posts, Replies, Media.
- Owner mode exposes Edit profile and character settings.
- Settings visibly separate public fields from owner-only behavior.
- Avatar panel demonstrates Persona Sigil, uploaded crop, processing, failure, remove, and fallback behavior.

## 07. Search and notifications

- Search and notifications share one review scene but remain separate screens.
- Search has Characters and Posts tabs, result, loading, and no-result states.
- Notifications show the active character context and an explicit switcher.
- Unread state uses weight, dot, and accessible text, not color alone.

## 08. Messages

- Desktop displays conversation list and selected conversation.
- Mobile displays either list or detail and always provides a back action.
- Sender identity is visible over the composer.
- Read watermark is subtle text, not a floating bubble badge.
- Empty, blocked, sending, failed, and read states are specified.

## 09. Admin

- The admin queue uses a dense operational table at desktop and stacked labeled rows on mobile.
- Open, reviewing, resolved, and dismissed states are visually distinct and text-labeled.
- Action confirmation names whether content, character, or account is affected.
- No consumer marketing decoration appears in this scene.

## Breakpoint behavior

| Feature | Desktop | Tablet | Mobile |
|---|---|---|---|
| navigation | labeled left rail | icon rail | bottom nav |
| contextual rail | only with live actionable data | normally hidden | hidden |
| modal | centered, max 560px | centered or sheet | bottom sheet |
| character editor | sticky specimen + form | specimen + form | stacked specimen + form |
| profile header | side-by-side | side-by-side | stacked |
| DM | list + detail | list + detail when space permits | separate screens |
| admin queue | table | compact table | labeled stacked rows |

## Screenshot evidence contract

The required matrix is 36 images:

- nine scene keys
- desktop 1280x900 and mobile 375x812
- light and dark theme

Tablet 768x1024 is a manual spot check for shell collapse and overflow. Evidence lives under `docs/design/evidence/screenshots/` and is indexed by `docs/design/evidence/README.md`.
