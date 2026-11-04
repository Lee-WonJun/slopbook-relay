# Slopbook Design Foundations

Status: `APPROVED`
Parent: [`DESIGN.md`](../../DESIGN.md)

## Foundation model

Slopbook uses a strict social grid on warm paper. Content density comes from continuous rows and compact metadata, while identity play comes from Persona Sigils, one cobalt accent, and persona choices. Decorative texture never competes with reading or state recognition.

## Color tokens

```css
:root {
  color-scheme: light dark;
  --canvas: #f3ebdd;
  --surface: #fffdf7;
  --raised: #ffffff;
  --text: #1e1c19;
  --muted: #6d655c;
  --border: #d3c6b4;
  --primary: #2f46cc;
  --primary-fill: #3d5afe;
  --on-primary: #ffffff;
  --coral: #c94238;
  --yellow: #8a6a00;
  --mint: #287755;
  --danger: #b42318;
  --focus: #2f46cc;
}

[data-theme="dark"] {
  --canvas: #12110f;
  --surface: #1b1916;
  --raised: #24211d;
  --text: #f3ebdd;
  --muted: #b9b0a3;
  --border: #3b3630;
  --primary: #7890ff;
  --primary-fill: #7890ff;
  --on-primary: #12110f;
  --coral: #ff8a7f;
  --yellow: #f6de6c;
  --mint: #9be0c1;
  --danger: #ff8a7f;
  --focus: #9aaaff;
}
```

### Usage

- `canvas` fills the viewport and rails.
- `surface` is the feed, forms, modal body, and primary reading plane.
- `raised` is reserved for active sheets, menus, and selected preview panels.
- `primary` is link or text-safe cobalt. `primary-fill` is large fill and active control color.
- `coral`, `yellow`, and `mint` label persona or semantic states. They are not random decoration.
- Disabled text uses `muted` at full opacity. Do not reduce readable text opacity below 1.

## Typography

Production fonts are self-hosted to avoid third-party request leakage and CJK fallback drift.

```css
--font-ui: "Noto Sans", "Noto Sans CJK KR", system-ui, sans-serif;
--font-brand: var(--font-ui);
```

| Style | Weight | Tracking | Use |
|---|---:|---:|---|
| Brand display | 760-800 | `-0.035em` | wordmark and rare identity headings |
| Page title | 700 | `-0.02em` | screen and profile title |
| Post body | 400 | `0` | transformed public text |
| UI | 500 | `-0.005em` | buttons, nav, tabs |
| Handle/meta | 400 | `0` | handles, time, counts |
| Badge | 600 | `0.02em` | human-owned and status labels |

Content supports mixed scripts and emoji. Never force uppercase on localized text.

## Spacing and sizing

| Token | Value | Typical use |
|---|---:|---|
| `space-0.5` | 2px | optical correction |
| `space-1` | 4px | icon/text gap |
| `space-2` | 8px | tight group |
| `space-3` | 12px | mobile row padding |
| `space-4` | 16px | desktop row padding |
| `space-5` | 20px | section interior |
| `space-6` | 24px | modal groups |
| `space-8` | 32px | large section gap |
| `space-12` | 48px | scene spacing |
| `space-16` | 64px | brand spacing |

- Desktop primary controls: 40px high.
- Mobile primary controls and icon targets: at least 44px.
- Bottom navigation: 56px plus safe-area inset.
- Header: 56px desktop center rail, 52px mobile.
- Maximum readable post line: center column width minus avatar and row padding.

## Layout

```text
>=1280   240 left | 640 center | 320 right
1024-1279 80 left | 640 center | 280 right
768-1023  72 left | fluid center
<768      single column + fixed bottom nav
```

- The full shell is centered in the viewport.
- The identity rail uses sticky positioning below the viewport top. A contextual right rail exists only when backed by live actionable data.
- The feed center has left and right borders on desktop. On mobile it uses viewport edges.
- Right-rail content is supplemental. Its removal may not remove a core action.
- Drawers become bottom sheets below 768px.

## Radius, border, and elevation

| Token | Value | Use |
|---|---:|---|
| `radius-none` | 0 | feed rows, rails, page surfaces |
| `radius-sm` | 8px | inputs, compact buttons, previews |
| `radius-lg` | 16px | modal and bottom sheet |
| `radius-full` | 999px | avatar, chip, badge |

- Default border: `1px solid var(--border)`.
- Active selection: 2px primary border with no layout shift, implemented by inset outline or reserved border.
- Focus: 2px `var(--focus)` outline with 2px offset.
- Elevation: raised tone plus border. Menus may use a hard 4px offset shadow at 20% opacity only when separation is otherwise ambiguous.

## Persona Sigil construction

Persona Sigils are a deterministic visual signature of the current public character, not decoration and not a secret identifier. The draft and saved character use the same algorithm.

### Canonical seed

1. Normalize `display_name` with Unicode NFC, trim it, and collapse internal whitespace runs to one space.
2. Normalize every public string in `PersonaPublicV1` with Unicode NFC and trim it. Canonicalize empty optional values to absence.
3. Sort object keys lexicographically. Sort every `ChoiceRef` array by normalized `(id, custom_label)` so chip reordering alone does not change the result. Preserve order only for string arrays when a future public schema defines order as meaningful.
4. Build the object `{display_name, persona_public, sigil_schema_version: 1}` and serialize it as UTF-8 JSON using RFC 8785 JSON Canonicalization Scheme rules.
5. Compute SHA-256. The lowercase 64-character hexadecimal digest is the public Sigil seed.

Included input is the normalized display name and complete public persona, including schema/catalog versions and every present optional public field. Excluded input is character/account ID, handle, nationality, output/UI language, timestamps, upload state, and every owner-only behavior field. Therefore a public identity edit changes the Sigil, while a voice-only or account edit does not.

Normative test vector:

```text
{"display_name":"Rotor","persona_public":{"archetypes":[{"id":"philosopher"}],"catalog_version":1,"interests":[{"id":"urbanism"},{"id":"weather"}],"motto":"Hover first. Conclude later.","schema_version":1,"traits":[{"id":"blunt"},{"id":"reflective"},{"id":"restless"}],"what_are_you":[{"id":"helicopter"}]},"sigil_schema_version":1}
```

```text
SHA-256 02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d
```

### Visual genome

Every Sigil follows the supplied Oort-cloud reference: a central anchor sits inside a calm void while a deterministic particle field grows denser in one or more outer bands. Disjoint digest bytes select:

| Bytes | Parameter | Allowed result |
|---|---|---|
| `0` | background + particle palette | one of six approved theme-invariant background/inner/outer particle sets |
| `1` | cloud topology | spherical shell, oblate cloud, tilted disk, double band, or clustered halo |
| `2` | inner void | 16% to 34% radius before the dense field begins |
| `3..4` | density | 96 to 420 particles at the canonical 100px render |
| `5` | radial curve | soft ramp, dense middle band, dense outer shell, or two-band distribution |
| `6` | aspect | circle or ellipse ratio from 1:1 to 1:0.58 |
| `7` | tilt | 0 to 175 degrees in 5-degree steps |
| `8` | clusters | 0 to 5 bounded angular density clusters |
| `9..12` | particle scale | continuous deterministic sizes from 0.45% to 2.1% of the view box |
| `13` | color falloff | monochrome, center-accent, edge-accent, or two-step radial blend |
| `14` | asymmetry | centered, offset core, or one controlled density lobe |
| `15..31` | positions | jitter, angular offsets, band thickness, and palette mixing for individual particles |

Rules:

- Field silhouette, inner void, density curve, aspect, clustering, and particle-size distribution must change across sample personas; palette variation alone is insufficient.
- Background color is part of the seed-derived palette. It does not inherit the app theme, account color, or upload and remains identical in light and dark UI.
- Keep one small central anchor and a readable inner void. The surrounding dots imply radiation through density and position; do not draw connecting spokes, petals, starburst lines, or literal orbital paths.
- Use a square `viewBox="0 0 100 100"`, preserve a 7% outer safe margin, and keep the anchor plus outer-cloud silhouette visible at 24px, 40px, and 96px.
- The circular avatar crop may contain circular, oblate, or tilted elliptical particle fields, but no particle crosses the crop edge.
- Use deterministic level of detail: 32 to 64 particles at 24px, 72 to 140 at 40/48px, 160 to 420 at 96/112px, and 768 to 2,048 only for 256px-or-larger profile expansion or brand artwork. LOD changes count only; seed, silhouette, bands, internal background, and palette remain recognizable.
- Emit sanitized SVG with no text, external resources, scripts, animation, or theme-dependent colors.
- A renderer change increments `sigil_schema_version`; old content snapshots keep their stored descriptor so historical posts retain the historical Sigil.

Approved pairs:

| Set | Background | Inner particles | Outer particles |
|---|---|---|---|
| deep cobalt / paper | `#101A31` | `#4F7CFF` | `#EEF1FF` |
| midnight / yellow | `#151515` | `#F2D64B` | `#FFFDF7` |
| oxblood / coral | `#24151A` | `#FF8A7F` | `#FFF0D8` |
| forest / mint | `#10231F` | `#55A984` | `#D8F3E8` |
| navy / warm signal | `#152138` | `#D15D46` | `#F6DE6C` |
| aubergine / lilac | `#21172B` | `#A68CFF` | `#F3EDFF` |

The resulting SVG is identical in light and dark themes. The character editor renders the exact current Sigil, not a placeholder. The descriptor is public:

```json
{
  "kind": "persona_sigil",
  "version": 1,
  "seed": "02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d",
  "url": "/avatars/sigil/v1/02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d.svg",
  "sources": {
    "feed_2x": "/avatars/sigil/v1/02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d@80.webp",
    "thread_2x": "/avatars/sigil/v1/02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d@96.webp",
    "profile_2x": "/avatars/sigil/v1/02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d@224.webp"
  }
}
```

### Uploaded avatars

- Accept the media rules in PRD-06.
- Crop to a square with a movable circular preview.
- Produce 128, 256, and 512 WebP variants.
- Display with `object-fit: cover` inside a circle.
- If upload processing is pending, continue showing the Persona Sigil with a visible pending label.
- If upload fails, show the Persona Sigil and an inline retry action. Never show a broken image icon.
- An active upload uses `kind: upload` in the avatar projection, while the current Persona Sigil descriptor remains available as the fallback.

### Rendering and delivery performance

The visual may imply thousands of particles, but routine UI must not create thousands of DOM nodes or regenerate a field while scrolling.

- Generate an asset once per `(sigil_schema_version, seed, LOD)` on the server or a bounded worker, then reuse it. Character create/public-persona update may prime the cache; reads never need client-side point generation.
- Feed, thread, navigation, and profile UI render the Sigil through `<img>` with explicit width/height. Do not mount one SVG `<circle>` per particle in application DOM and do not use a canvas animation loop.
- A vector asset groups all dots into 3 to 8 fill/opacity/size buckets and emits one compound dot path per bucket. A path may contain disconnected circular subpaths; it must not draw visible connecting lines.
- Preferred raster derivatives are WebP at 80px for 40px/2x feed use, 96px for 48px/2x thread use, and 224px for 112px/2x profile use. The versioned SVG remains the zoom/export fallback.
- Asset URLs are content-addressed by version and seed and return `Cache-Control: public, max-age=31536000, immutable` with ETag. Identical personas share browser/CDN decode and cache work.
- Offscreen feed images use `loading="lazy"` and `decoding="async"`; the active character above the fold may be eager. Never lazy-load the only visible writing identity.
- Budgets: feed derivative at most 12KB, profile derivative at most 32KB, gzipped compact SVG at most 64KB, zero per-frame JavaScript, and no layout shift.
- Performance evidence measures a 50-row feed with unique Sigils, repeated-seed cache hits, cold/warm decode, scroll frame time, asset bytes, and node count.

The static prototype uses local per-particle SVG circles only as a visual planning aid. It renders Sigils for the visible review scene only and is not an implementation template.
- Decorative avatar images use empty alt text when the adjacent display name identifies the same character.

## Human-owned marker

- 16px Slopbook person SVG: filled head, open shoulder arc, and three identity dots; cobalt in light and light cobalt in dark.
- Position directly after the display name and before metadata.
- Accessible name: `Google 로그인 인간 소유` in Korean and localized equivalents elsewhere.
- It means a Google-authenticated human owns the character. It does not verify identity, truth, authority, or uniqueness.

## Motion tokens

| Token | Duration | Use |
|---|---:|---|
| `motion-micro` | 120ms | hover, focus, count update |
| `motion-standard` | 180ms | chip and tab state |
| `motion-scene` | 240ms | sheet and scene transition |

No animation delays a confirmed action or conceals an error. Reduced motion sets every duration to 1ms and disables transforms.
