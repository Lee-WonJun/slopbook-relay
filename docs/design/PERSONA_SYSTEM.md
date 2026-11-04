# Character editor, roster, and persona contracts

Status: `APPROVED`
Parent: [`DESIGN.md`](../../DESIGN.md)
Contract decision: [`ADR-002`](../adr/ADR-002-structured-persona-contract.md)
Sigil decision: [`ADR-003`](../adr/ADR-003-persona-sigil-and-random-persona.md)
Editor and guest revision: [`ADR-005`](../adr/ADR-005-character-roster-unified-editor-and-guest-persona.md)
Guest and first-post revision: [`ADR-007`](../adr/ADR-007-persistent-guest-selection-and-previewed-first-post.md)

## Product model

Slopbook has two persona modes:

- **Owned character:** account-private ownership, public profile and handle, full social capabilities, maximum five active characters per account.
- **Guest persona:** origin-scoped browser identity for signed-out text comments, with no account, handle, public profile, follow, post, reaction, notification inbox, or DM capability.

Both use the same versioned public and behavior field contracts. Public persona describes what readers may understand. Behavior controls expression and remains owner/browser-private.

## One-page character editor

Creation and editing are one continuous form. The product does not label sections as Roll, Make it yours, or Voice and does not show progress steps, starter decks, pins, group rerolls, or a separate review gate.

1. The editor opens with a complete valid local draft.
2. `Random` replaces the local display name, complete public persona, and complete behavior values without AI or network access.
3. Every generated value is editable in place. Every bounded multi-choice and text field shows `current / maximum` beside its label; optional fields are in a plainly labeled disclosure.
4. Subsequent Random actions replace the generated handle together with the name and persona. They preserve only the explicitly selected output language and any staged avatar upload, which are delivery settings rather than generated character traits.
5. Public edits update the Persona Sigil and compact public preview. Behavior edits never change the Sigil.
6. Owned creation ends with a server-backed `First post` preview. Any draft change marks it stale; `Create character` remains disabled until the current preview succeeds. The explicit create action publishes that exact text once, then enters the feed as the new active character.
7. `Create character` or `Save changes` is the only completion action. There is no separate review page.

The editor layout is two regions on wide screens: a sticky identity specimen with Sigil/name/summary and a form column. It becomes one column below 768px. Public persona and `Private voice settings` remain visibly separated without turning them into stages.

## Owned-character roster

- `GET /me` returns every owned character, `character_limit: 5`, and `remaining_character_slots`.
- The desktop left rail labels the region `Your characters`, shows `n / 5`, and renders every character as a direct selection row: 32px Sigil, display name, `@handle`, and up to two localized `What are you?` labels.
- Active state uses a 2px edge marker, surface change, and `aria-current`; it is not color-only.
- `New character` remains visible while slots remain and becomes a disabled `5 / 5 characters` control at capacity.
- Compact tablet navigation uses an avatar popover with the same roster. Mobile exposes the roster from the top-bar active-character control.
- Selecting a character updates all actor-bound requests and compose surfaces. It never navigates by cycling through an unknown list.
- Common ownership and list order never appear in a public projection.

## Guest persona editor

The signed-out `/characters` route shows the guest editor. The signed-out comment composer shows one compact identity row with the saved guest Sigil, display name, public summary, and `Edit guest character`, or a setup CTA when none is saved.

- Guest editing reuses `Random`, public persona fields, private voice fields, and output language.
- It omits handle, nationality, uploaded avatar, human-owned marker, profile preview/link, and account settings.
- `Random` and field edits mutate an unsaved draft. `Save guest character` atomically replaces the active guest; Cancel leaves the previous saved identity unchanged.
- The browser stores one active `GuestPersonaV1` in origin-scoped local storage. Clearing site data removes the persona and the anonymous-comment deletion secret.
- Every signed-out thread automatically uses that active guest. A thread never embeds the full editor or silently invents a fallback persona.
- Behavior is sent only for the comment rewrite request and is never copied into the public comment response or snapshot.
- A posted guest comment displays the snapshotted display name and Persona Sigil but is not a link.
- Turnstile remains a free, independently deployable anti-abuse boundary for signed-out comments. Missing production keys disable guest commenting, not public reading or authenticated actions.

## First-post preview states

| State | Preview | Create action | Recovery |
|---|---|---|---|
| loading | skeleton/status text | disabled | wait or retry after error |
| ready | exact transformed greeting | enabled | none |
| stale after edit | hidden old text + stale message | disabled | refresh current draft |
| preview error | no publishable text | disabled | retry without losing draft |
| creating/publishing | exact text retained | disabled | prevent duplicate action |
| character created, publish failed | exact text + error | retry only | refresh expired token then idempotent publish |

Preview and Sigil promises carry the draft that initiated them. A response updates state only when that draft still equals the editor's current draft. The 15-minute server token binds account, complete normalized draft, language, text, and AI metadata. Guest personas never enter this state machine because they cannot post.

## Local random generation

Random generation uses reviewed locale-specific catalogs and templates. It is a draft convenience, not AI generation.

```text
modifier + subject          Velvet Comet
place-or-time + role        Midnight Cartographer
material-or-weather + being Paper Fox
motion + machine-or-object  Hover Radio
```

- Normalize to Unicode NFC, collapse whitespace, and produce 1 to 40 graphemes.
- Use locale-aware spacing and word order; do not translate an assembled name.
- Filter profanity, slurs, impersonation-sensitive reserved names, control characters, and visually empty output.
- Avoid the previous ten page-session names when possible.
- Populate every required field, respect all cardinalities, avoid duplicate choices, and include a bounded compatible subset of optional fields.
- Preserve the explicitly selected output language and any staged avatar upload. Replace the generated handle together with the rest of the draft.
- Announce once: `{display name} filled. All fields are editable.`

## Reviewed catalog v2

Catalog v2 keeps the current structured contract while recovering the concrete, social-native range that worked in the legacy Slopbook form. Legacy demographics are not restored as required identity claims. Their useful options are adapted into `What are you?`, archetypes, traits, voice tones, and interests.

- `What are you?`: retains woman, man, non-binary, agender, genderfluid, and human, and includes helicopter, alien, AI, robot, animals, fictional beings, objects, concepts, weather, and vehicles.
- Archetypes: adds creator, developer, designer, founder, streamer, idol fan, gamer, and anonymous account to the observer, maker, and outsider roles.
- Traits: adds cynical, mischievous, serious, obsessive, attention-seeking, provocative, unhinged, lethargic, self-loathing, bluffing, sensitive, and delusional.
- Voice tones: adds short and dry, talkative, sarcastic, close-friend, otaku, meme-heavy, sudden rant, forum slang, Twitter-style, monologue, and flat AI tone.
- Interests: adds daily life, memes, gaming, anime, K-POP, idols, development, AI, startups, design, dating, restaurants, cafes, fitness, travel, movies, fashion, careers, stocks, cats, and dogs.

The editor groups these options by meaning. Category names organize scanning only and are never stored. Existing catalog v1 personas remain valid; newly randomized drafts write `catalog_version: 2`.

## `What are you?` catalog

Real, fictional, animal, object, concept, weather, and vehicle options share one visual hierarchy. Initial IDs:

```text
woman, man, non_binary, agender, genderfluid, human,
cat, dog, fox, bird, gremlin, ghost, deity, alien, plant,
ai, robot,
object, concept, weather, vehicle, helicopter, custom
```

- Select 1 to 3.
- `custom` requires a 1 to 30 grapheme `custom_label`.
- Pronouns are independent and are never inferred from form, name, Sigil, or nationality.

## `ChoiceRef`

Built-in:

```json
{"id":"helicopter"}
```

Custom:

```json
{"id":"custom","custom_label":"moonlit vending machine"}
```

- `id` is valid for the field or equals `custom`.
- `custom_label` is required only for `custom`, normalized and trimmed, 1 to 30 graphemes.
- Duplicate built-in IDs and case-folded duplicate custom labels are rejected.
- Values render only as text and are untrusted prompt data.

## `PersonaPublicV1`

```json
{
  "schema_version": 1,
  "catalog_version": 1,
  "what_are_you": [{"id":"helicopter"},{"id":"custom","custom_label":"part-time oracle"}],
  "archetypes": [{"id":"philosopher"}],
  "traits": [{"id":"restless"},{"id":"reflective"},{"id":"blunt"}],
  "bio": "Circling the city until the idea lands.",
  "pronouns": "it/they",
  "interests": [{"id":"urbanism"},{"id":"philosophy"},{"id":"weather"}],
  "values": [{"id":"curiosity"}],
  "dislikes": [{"id":"small_talk"}],
  "setting": {"id":"present_day"},
  "home_base": "Above the ring road",
  "motto": "Hover first. Conclude later."
}
```

| Field | Required | Cardinality / limit |
|---|---:|---|
| `schema_version` | yes | integer `1` |
| `catalog_version` | yes | supported positive integer |
| `what_are_you` | yes | 1 to 3 `ChoiceRef` |
| `archetypes` | yes | 1 to 2 `ChoiceRef` |
| `traits` | yes | 3 to 5 `ChoiceRef` |
| `bio` | no | 1 to 160 graphemes when present |
| `pronouns` | no | 1 to 24 graphemes when present |
| `interests` | no | 0 to 8 `ChoiceRef` |
| `values` | no | 0 to 5 `ChoiceRef` |
| `dislikes` | no | 0 to 5 `ChoiceRef` |
| `setting` | no | one `ChoiceRef` |
| `home_base` | no | 1 to 40 graphemes when present |
| `motto` | no | 1 to 60 graphemes when present |

Empty optional strings and arrays canonicalize to absence.

## `PersonaBehaviorV1`

```json
{
  "schema_version": 1,
  "voice_tones": [{"id":"clipped"},{"id":"philosophical"}],
  "sentence_shape": "short",
  "formality": "neutral",
  "humor_style": "dry",
  "emoji_level": "light",
  "social_mode": "replier",
  "reply_posture": "curious",
  "catchphrases": ["Rotors agree."],
  "verbal_tics": ["brief altitude metaphor"],
  "avoid_style": "No fake quotations or invented statistics.",
  "custom_rule": "End with a landing image"
}
```

| Field | Required | Cardinality / limit |
|---|---:|---|
| `schema_version` | yes | integer `1` |
| `voice_tones` | yes | 1 to 3 `ChoiceRef` |
| `sentence_shape` | yes | `short`, `mixed`, `long` |
| `formality` | yes | `casual`, `neutral`, `formal` |
| `humor_style` | no | catalog enum |
| `emoji_level` | no | `none`, `light`, `regular`, `heavy` |
| `social_mode` | no | `lurker`, `replier`, `broadcaster`, `collector`, `debater`, `cheerleader` |
| `reply_posture` | no | `supportive`, `curious`, `challenging`, `playful` |
| `catchphrases` | no | 0 to 3 strings, each 1 to 60 graphemes |
| `verbal_tics` | no | 0 to 3 strings, each 1 to 30 graphemes |
| `avoid_style` | no | 1 to 60 graphemes when present |
| `custom_rule` | no | 1 to 30 graphemes when present |

Behavior is excluded from public DTOs, content snapshots, analytics, structured logs, errors, and unrelated moderation exports.

## `GuestPersonaV1`

```json
{
  "schema_version": 1,
  "display_name": "Midnight Rotor",
  "output_language": "ko",
  "persona_public": {
    "schema_version": 1,
    "catalog_version": 1,
    "what_are_you": [{"id":"helicopter"}],
    "archetypes": [{"id":"philosopher"}],
    "traits": [{"id":"restless"},{"id":"reflective"},{"id":"blunt"}]
  },
  "persona_behavior": {
    "schema_version": 1,
    "voice_tones": [{"id":"clipped"}],
    "sentence_shape": "short",
    "formality": "neutral"
  }
}
```

- `schema_version` is `1`.
- Display name, output language, public persona, and behavior obey the same limits as an owned character.
- Unknown fields, handle, nationality, avatar upload, owner/account identifiers, and profile URLs are rejected.

## Public and owner projections

`PublicCharacter` contains `id`, handle, display name, public avatar descriptor, output language, complete `PersonaPublicV1`, persona version, and `human_owned: true` only. `OwnedCharacter` wraps `PublicCharacter` and adds complete `PersonaBehaviorV1`.

`GuestPersonaPublicV1` contains only:

```json
{
  "schema_version": 1,
  "display_name": "Midnight Rotor",
  "output_language": "ko",
  "persona_public": {
    "schema_version": 1,
    "catalog_version": 1,
    "what_are_you": [{"id":"helicopter"}],
    "archetypes": [{"id":"philosopher"}],
    "traits": [{"id":"restless"},{"id":"reflective"},{"id":"blunt"}]
  },
  "avatar": {
    "kind": "persona_sigil",
    "version": 1,
    "seed": "lowercase-sha256",
    "url": "/avatars/sigil/v1/lowercase-sha256.svg"
  }
}
```

- Public character/profile/feed endpoints return only public projections.
- `/me` and authenticated create/update responses may return behavior to the owner.
- Owned posts/comments/messages snapshot the public persona, version, and current public Sigil descriptor.
- Guest comments snapshot `GuestPersonaPublicV1`; they never snapshot behavior or create a profile route.
- Later edits never mutate historical snapshots.

## AI priority and safety

Persona fields are untrusted expression data. Processing priority remains:

1. Privacy and safety.
2. Preserve human meaning, target, facts, numbers, claims, negation, and uncertainty.
3. Required output language and schema.
4. Persona vocabulary, rhythm, formality, humor, and social posture.

Interests, values, dislikes, settings, and custom text may influence expression and reaction choice. They cannot add or omit material facts, reverse sentiment, fabricate experience, or override the contract.

## Analytics

Allowed editor events: editor open mode, Random count, canonical option IDs, completion booleans, field counts, validation category, latency, and save success. Never emit generated names, Sigil seed, custom labels, bio, pronouns, home base, motto, catchphrases, verbal tics, avoid style, custom rule, complete persona objects, guest deletion secrets, or raw content.
