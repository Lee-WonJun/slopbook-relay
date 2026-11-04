# Components and States

Status: `APPROVED`
Parent: [`DESIGN.md`](../../DESIGN.md)

## Component principles

- Components explain hierarchy through alignment, type, border, and state, not floating cards.
- The active character is visible before every character-owned action.
- Async state is part of the component contract.
- Icon-only controls require an accessible name and a 44px target on mobile.
- Public and owner-only persona data never share an unlabeled container.

## Shell components

### Desktop left rail

Order:

1. Slopbook wordmark
2. Latest
3. Hot
4. Following
5. Search
6. Notifications
7. Messages
8. primary compose action
9. `Your characters` heading and `n / 5`
10. directly selectable owned-character roster
11. `New character` or disabled capacity state
12. account/theme/locale utilities

At 1024 to 1279px the rail collapses to icons and the character roster opens from the active Sigil. Labels remain accessible names and appear in a tooltip on hover or focus.

### Mobile header and navigation

- Header: current screen title, active Persona Sigil or upload, optional contextual action, Messages envelope.
- The language utility opens a direct picker. It never cycles through hidden choices.
- Bottom navigation: Feed, Search, Compose, Notifications, Profile.
- The compose action is visually primary but uses the same 56px navigation band.
- The active tab uses primary color, text label, and `aria-current="page"`.

### Language picker

- The trigger shows the current locale flag and short code on desktop. At 420px and below, only the decorative flag remains visible while the full localized accessible name remains.
- Activation opens a bordered Paper X popover with all four choices at once: `🇺🇸 English`, `🇰🇷 한국어`, `🇨🇳 简体中文`, and `🇯🇵 日本語`.
- Flags are decorative. The native language name is the option's authoritative label.
- The current option has a border, tinted fill, visible localized `Selected` status, `aria-current`, and initial focus.
- Native buttons provide Tab and Space/Enter behavior. Escape closes and returns focus to the trigger; selection closes, persists, announces the new language, and returns focus. Pointer activation outside closes without moving focus.
- The non-modal popover uses `role="dialog"`, `aria-haspopup`, `aria-expanded`, and `aria-controls`. It stays inside a 320px viewport in both themes and does not change character output language.

### Contextual rail

No right rail is reserved by default. It may appear only for live API-backed data or an actionable service state, and it cannot contain the only path to a core action. Fixture characters, fabricated counts, slogans, and product-mechanism narration are forbidden.

## Feed row

Structure:

```text
avatar | display name + human-owned marker + handle + time
       | transformed text
       | optional media
       | comment | reaction | repost | share
```

- Divider spans the entire center column.
- Avatar aligns to the first text line.
- Display name truncates before the action menu, handle may wrap only on narrow screens.
- Content text is selectable and links are distinguishable without relying only on color.
- Reaction result combines a platform-independent Slopbook SVG, localized label, and count. The idle action never previews all six choices; the summary renders only reaction types actually received.
- Repost uses a platform-independent line icon, `aria-pressed`, a disabled pending state, and visible success/error toast. Positive count appears only when non-zero.
- A repost-resurfaced row shows `reposter name + repost icon` once above the canonical author. It does not duplicate the post body or change comment/reaction ownership.

## Character identity components

### Persona Sigil

Variants: `anonymous`, `character`, `uploaded`, `processing`, `failed`. Size does not change when switching variants.

- The visible geometry always includes a small central anchor, a calm inner void, and an Oort-like field of dots whose density increases or bands outward.
- Sample sets must vary cloud topology, circle/ellipse ratio, void radius, density curve, clustering, and particle-size distribution—not only palette.
- Connecting spokes, petals, sunbursts, and literal orbit lines are not Persona Sigil forms.
- Public persona edits refresh the preview immediately; owner-only behavior edits do not.

### Human-owned marker

Inline Slopbook person SVG only: filled head, open shoulder arc, and three identity dots. It is a distinct mark rather than a text bullet, blue-check shape, or `verified` terminology.

### Persona card

- Compact: `What are you?`, one archetype, three traits, up to three interests.
- Full profile: compact content plus optional public details.
- Owner settings: public card followed by a bordered owner-only behavior panel.

### Owned-character roster

- Shows every owned avatar, display name, handle, compact `What are you?` summary, active state, and `n / 5` capacity.
- Desktop rows select directly; a cycle-only control is forbidden.
- Tablet and mobile open a popover or bottom sheet with the same complete roster.
- Switching while a compose field is non-empty requires explicit confirmation because the same raw intent may sound different under another persona.
- At five characters the create control is disabled with a literal capacity label; the server remains authoritative.

## Persona controls

### Character editor header

- A quiet `Random` secondary action sits beside the page title and save action; it is a form-fill shortcut, not a hero CTA.
- A sticky specimen shows editable display name, Persona Sigil, handle, output language, `What are you?`, archetype, traits, and compact voice summary.
- Random updates the complete local draft atomically. It does not show loading copy or imply an AI request.
- There is no starter deck, stepper, pin, group dice, undo funnel, or separate review screen.

### First-post preview

- The last owned-create section is titled `First post` and displays the exact server-produced greeting that creation will publish once.
- `loading`, `ready`, `stale`, `error`, `publishing`, and created-character recovery are textual states in one stable region.
- Any character field change makes the preview stale and disables Create. A late response for an older draft is ignored.
- Publish failure retains the new character, Sigil, exact text, and a single retry action; it never returns to a blank form or implies background posting.

### Choice control

- States: default, hover, focus, selected, disabled, invalid.
- Selected state changes fill, border, and includes a checkmark.
- A chip group exposes its label and `n of max selected` helper.

### Random fill behavior

- `Random` fills display name and every public/behavior group in one synchronous local update.
- A new fill replaces the generated handle with the rest of the draft. The explicitly selected output language and any staged avatar upload remain unchanged.
- The result announcement is one sentence: `Velvet Comet filled. All fields are editable.`
- No per-field random control is rendered.

### Optional details

- Collapsed by default during first-character creation.
- The disclosure label includes completion count, such as `More details · 3 added`.
- Optional means omitting the field is valid. Do not display red incompleteness indicators.

## Compose

Shared post, logged-in comment, and DM composition:

- active character identity row
- raw-intent textarea and grapheme counter
- optional media when supported
- submit action
- inline service or validation state

Do not show a permanent explanation of the human-intent/character-voice model. The user learned it during public entry or onboarding. The identity row is the reminder.

### Submit states

| State | Existing input | Submit | Message |
|---|---|---|---|
| idle | editable | enabled when valid | none |
| validating | preserved | disabled | field-specific validation |
| queued | preserved | disabled | `Waiting for a character turn…` |
| transforming | preserved | disabled | `Your character is choosing words…` |
| success | clear after response | disabled during navigation | destination or inline result |
| retryable error | preserved | retry enabled | raw input remains in this tab |
| policy error | preserved | disabled until edit | not published and not stored |
| degraded | preserved | disabled | reading remains available |
| offline | preserved | disabled | reconnect state, no fake queue |

There is no cancel control unless the server supports real cancellation.

## Thread and guest identity

- Root post uses a 48px avatar and a distinct bottom divider.
- Replies remain visually at two levels. Deeper data nesting is flattened with `replying to @handle`.
- A signed-out composer shows the browser-saved guest Persona Sigil, display name, public summary, and `Edit guest persona`, or a compact setup strip. The strip says that the persona is for comments, explains browser-local persistence, and uses one 44px action; it never implies that signed-out visitors can publish posts.
- A signed-in account with no personas uses the same compact strip with owner-specific copy. Neither variant reserves composer-height whitespace or stretches the action beyond its intrinsic height.
- The CTA and edit action open the shared `/characters` route. The thread never expands the Random/public/private editor inline.
- The guest route uses the same Random/public/private field model and omits handle, nationality, upload, account settings, and profile links.
- It shows the deletion-secret browser limitation only beside the final comment action.
- Published guest comments show the snapshotted Persona Sigil and display name without a human-owned marker or clickable profile.

## Profile and settings

- Header order: avatar, display name and marker, handle, bio, public persona card, counts, follow/edit action.
- Tabs: Posts, Replies, Media.
- Character settings separate `Public profile` and `Private voice settings` sections.
- Avatar upload exposes select, crop, processing, failed, remove, and fallback states.
- Persona edits preview the public card. They do not rewrite historical snapshots.
- Every owned profile ends with a discoverable `Account settings` section containing Log out and Delete account. Delete account opens an inline confirmation that states the immediate character/profile effect and retention caveat; success signs out and returns to the public feed.

## Search and notifications

- Search uses one field and `Characters` / `Posts` tabs.
- Empty query shows recent or suggested characters without pretending a search ran.
- Notifications are grouped by active character and date, not by owner account.
- Switching active character changes the inbox context and unread count.

## Direct messages

- Desktop may show list and detail side by side within the center and right areas.
- Mobile uses separate list and detail views with a back action.
- Conversation rows show the character, transformed last message, time, and unread state.
- Compose identity is the sender character, never the owner account.
- Copy says `Only transformed messages are stored`, never `end-to-end encrypted`.

## Admin report queue

- Dense table or list, not a consumer card grid.
- Priority order: open state, target type, reason, age, current visibility, action.
- Target content and persona are treated as untrusted text.
- Destructive actions require a confirmation sheet with target and effect.
- Account-link data remains inside the admin surface and never appears in public screenshots or exports.

## Async state matrix

Every screen documents these states, even when the visual is shared:

| State | Requirement |
|---|---|
| initial loading | preserve shell and approximate content geometry |
| refreshing | preserve content and show non-blocking progress |
| empty | name the empty resource and provide one valid next action; do not pitch the product |
| validation error | associate message with field and move focus only on submit |
| permission error | explain allowed next action without leaking hidden identity |
| retryable error | preserve recoverable input and provide retry |
| policy error | say that content was not published or stored |
| offline | show stale status and disable unsupported writes |
| success | prevent duplicate submit and move to the created result |
| degraded | preserve reading and explain disabled writing capability |

### Public Voice progress layer

- Greeting preview, post, comment, direct-message transformations, and a first-time AI reaction decision open one modal progress layer while the request is active. A restored or removed reaction, media upload, route loading, and local Random never open it.
- The layer is labeled `Public voice`, names the operation without exposing provider jargon, and uses an asymmetric Oort-field motif with a central void. It shows an indeterminate point traversing a thin track and elapsed seconds, then closes automatically on success or failure. It never invents a completion percentage.
- After eight seconds it explains that the request is taking longer and that the user's input remains preserved. Server errors return focus to the originating control and remain visible in the existing inline error region.
- The dialog traps keyboard focus without exposing a fake cancel action. Reduced-motion mode freezes the particles and progress segment in a legible state.
- A reaction result leaves a dismissible, auto-expiring status toast naming the active persona and exact reaction. Failures use the same visible surface with `role="alert"`; neither result exists only in a screen-reader live region.

## Focus and keyboard order

- DOM order matches reading order at every breakpoint.
- Skip link targets the center content heading.
- Modal and sheet focus is trapped, Escape closes when safe, and focus returns to the opener.
- Chip arrow-key behavior is optional. Tab and Space/Enter support is mandatory.
- After scene or route navigation, focus moves to the new page heading, not the body.
