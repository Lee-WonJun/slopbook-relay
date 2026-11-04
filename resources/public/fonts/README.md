# Slopbook web fonts

The UI uses self-hosted subsets so the four supported interface locales do not
depend on third-party requests. Korean feed and comment copy also has a complete
Hangul-range face because user content is not limited to the interface catalog.

- `noto-sans-ui-en.woff2`, `-ko`, `-ja`, and `-zh` are derived from Noto Sans
  CJK 2.262 and distributed under the SIL Open Font License 1.1 in
  `OFL-Noto-Sans.txt`.
- `noto-sans-content-ko.woff2` contains the complete modern and historic Hangul
  ranges and is loaded only for content explicitly marked `lang="ko"`.
- `fraunces-72pt-semibold.woff2` is Fraunces 1.197 and is distributed under the
  SIL Open Font License 1.1 in `OFL-Fraunces.txt`.
- `scripts/build-font-subsets.sh` records the pinned upstream revisions and
  regenerates these files when interface copy changes.
