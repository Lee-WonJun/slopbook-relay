# Copy, Internationalization, and Accessibility

Status: `APPROVED`
Parent: [`DESIGN.md`](../../DESIGN.md)

## Brand copy

Official and untranslated:

```text
Character. Beyond Anonymity.
```

- Preserve capitalization and both periods.
- It may wrap after `Character.`.
- Do not shorten it to initials.
- Do not translate it in Korean, Simplified Chinese, or Japanese UI.
- It appears in brand assets and long-form entry material, not normal application subtitles, empty states, rails, or composers.

## Voice

Copy is short and playful for character creation, precise for privacy, safety, AI limitations, and destructive actions.

| Context | Use | Avoid |
|---|---|---|
| brand material | `Your intent. Their voice.` | repeating it below routine page titles |
| processing | `Your character is choosing words…` | `Training on your input…` |
| retryable failure | `That did not publish. Your input is still in this tab.` | `Saved for later.` |
| human-owned | `Google 로그인 인간 소유` | `Verified person` |
| DM | `Only transformed messages are stored.` | `End-to-end encrypted.` |
| private behavior | `Private voice settings` | `Secret prompt` |

## Key localized labels

| Key | English | Korean | Simplified Chinese | Japanese |
|---|---|---|---|---|
| `persona.what_are_you` | What are you? | 무엇으로 살래요? | 你想成为什么？ | 何になりたい？ |
| `persona.what_help` | A person, a ghost, a machine, a weather system. Pick what fits. | 사람, 유령, 기계, 날씨도 좋아요. 지금 맞는 걸 골라요. | 人、幽灵、机器、天气都可以。选择适合你的。 | 人、幽霊、機械、天気でも。今しっくりくるものを選んで。 |
| `persona.randomize` | Random | 랜덤 | 随机 | ランダム |
| `persona.new` | New character | 새 캐릭터 | 新角色 | 新しいキャラクター |
| `persona.roster` | Your characters | 내 캐릭터 | 你的角色 | あなたのキャラクター |
| `persona.capacity` | {count} / 5 characters | 캐릭터 {count} / 5 | {count} / 5 个角色 | キャラクター {count} / 5 |
| `anonymous.create` | Create guest character | 게스트 캐릭터 만들기 | 创建访客角色 | ゲストキャラクターを作る |
| `anonymous.edit` | Edit guest character | 게스트 캐릭터 편집 | 编辑访客角色 | ゲストキャラクターを編集 |
| `persona.greeting_title` | First post | 첫 인사 게시물 | 第一条帖子 | 最初の投稿 |
| `persona.greeting_help` | This exact preview is posted once when the character is created. | 캐릭터를 만들면 이 미리보기 그대로 한 번 게시됩니다. | 创建角色时会按此预览内容发布一次。 | キャラクター作成時に、このプレビューどおり一度だけ投稿されます。 |
| `persona.greeting_stale` | The character changed. Refresh the preview before creating it. | 캐릭터 설정이 바뀌었습니다. 만들기 전에 미리보기를 새로 만들어 주세요. | 角色设置已更改。创建前请刷新预览。 | キャラクター設定が変わりました。作成前にプレビューを更新してください。 |
| `persona.public` | Public persona | 공개 페르소나 | 公开人格 | 公開ペルソナ |
| `persona.private` | Private voice settings | 비공개 목소리 설정 | 私密语气设置 | 非公開の声の設定 |
| `badge.human_owned` | Owned by a human signed in with Google | Google 로그인 인간 소유 | 由使用 Google 登录的人类拥有 | Google でログインした人間が所有 |
| `action.react` | React | 반응하기 | 反应 | リアクション |
| `compose.processing` | Your character is choosing words… | 캐릭터가 말을 고르는 중… | 角色正在选择措辞… | キャラクターが言葉を選んでいます… |
| `compose.failed_tab` | Not published. Your input remains only in this tab. | 게시되지 않았어요. 원문은 이 탭에만 남아 있어요. | 未发布。原文只保留在此标签页中。 | 投稿されませんでした。原文はこのタブにだけ残っています。 |

Catalog option labels are translated, while IDs remain stable. User custom text is displayed as entered and is not machine-translated.

Footer labels are `Privacy`, `Terms`, `AI Policy`, `Community Guidelines`, and `Security`; localized labels retain the same routes. Legal prose may ship with an English normative baseline and reviewed translations, but navigation and essential rights/contact summaries must exist in all four launch locales.

## Persona language rules

- `What are you?` is playful and deliberately broad. Do not infer pronouns or gendered grammar from it.
- Pronouns are optional free text and public when supplied.
- Nationality and output language remain independent.
- Output language does not change UI locale.
- Interests, values, dislikes, bio, and behavior custom text are untrusted user content.
- Empty optional fields disappear rather than displaying `Not set` on public profiles.

## Internationalization

- Supported UI locales: `en`, `ko`, `zh-Hans`, `ja`.
- The header language control opens one direct picker that exposes all supported locales as decorative flag plus native language name. A cycle-only language control is forbidden.
- The picker labels, current-state text, accessible trigger name, and change announcement are translated. Selecting a locale updates `<html lang>` and origin-scoped local storage without changing character output language.
- Use ICU-style plural and parameter interpolation. Do not concatenate sentence fragments.
- All dates use locale and local timezone in UI, UTC RFC3339 in APIs.
- CJK text uses natural line breaking. Do not apply English word-breaking to CJK.
- Handles and canonical enum IDs are left-to-right tokens.
- Layout tests include 40-character display names, 20-character handles, mixed CJK/Latin/emoji, and long German-like fallback strings even though German is not a launch locale.

## Accessibility baseline

- Target WCAG 2.2 AA.
- Normal text contrast: at least 4.5:1.
- Large text and non-text controls: at least 3:1.
- Keyboard access covers public reading, login entry, character editor/roster, guest editor, compose, reaction, DM, report, policies, and settings.
- Minimum mobile pointer target: 44 by 44 CSS px.
- Visible focus uses a 2px focus outline with 2px offset.
- Skip link, landmarks, one page `h1`, ordered headings, form labels, and error associations are required.
- Modal and sheet focus is trapped and returned.
- The non-modal language picker initially focuses the selected language. Escape and selection return focus to its trigger, and pointer activation outside closes it without stealing focus.
- Loading and result updates use restrained live regions.
- First-post preview loading/stale/error/publishing changes use one named live region; Create exposes native disabled state until a current preview is ready.
- Decorative Persona Sigil SVG is hidden from assistive technology when the adjacent name already labels the character.

## Persona control semantics

- A group uses `fieldset` and `legend`, or an equivalent named composite.
- Choice chips expose selected state through native checkbox/radio semantics or `aria-pressed`.
- Selection count is programmatically associated with the group and rendered as `current / maximum` beside its label.
- Every bounded text field and media picker uses the same visible `current / maximum` convention.
- Choice controls expose selected state with a check mark, contrast, and `aria-pressed`; no state relies on color alone.
- Randomization announces one summary after the DOM update, including the generated display name and changed groups.

## Reduced motion

At `prefers-reduced-motion: reduce`:

- Persona Sigil particle breathing and outward density pulse stop.
- Scene and sheet transforms become immediate opacity changes or no animation.
- Reaction and randomization pulses stop.
- Skeleton shimmer becomes a static neutral block.
- State, selection, and progress remain visible through text and color-independent indicators.

## Responsive and zoom checks

- 320 CSS px: no page-level horizontal scrolling for core actions.
- 200% zoom: rails collapse according to available CSS width and no fixed overlay blocks content.
- 400% zoom: content reflows to a single reading column where browser behavior permits.
- Mobile safe-area insets apply to bottom navigation and sheets.
- Long text may increase component height. It may not be clipped to preserve a screenshot.

## Content safety and moderation

- Custom persona values render as escaped text.
- A character report covers public persona content as well as posts.
- Admin screens identify user content as untrusted and never render it as markup.
- Public screenshots and support exports exclude owner-only behavior and owner account linkage.
