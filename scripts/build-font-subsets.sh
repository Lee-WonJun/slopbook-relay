#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

noto_revision="f8d157532fbfaeda587e826d4cd5b21a49186f7c"
fraunces_revision="7ccdec31c6028118dce3e47fe864e3744460371d"
font_dir="$repo_root/resources/public/fonts"
copy_file="$work_dir/ui-copy.txt"

command -v curl >/dev/null
command -v pyftsubset >/dev/null
mkdir -p "$font_dir"

awk '1' \
  "$repo_root/src/slopbook/client/i18n.cljs" \
  "$repo_root/src/slopbook/client/randomizer.cljs" \
  "$repo_root/resources/public/js/locale-bootstrap.js" > "$copy_file"

for locale in KR JP SC; do
  curl --fail --silent --show-error --location \
    "https://raw.githubusercontent.com/notofonts/noto-cjk/$noto_revision/google-fonts/NotoSans${locale}%5Bwght%5D.ttf" \
    --output "$work_dir/NotoSans${locale}.ttf"
done

pyftsubset "$work_dir/NotoSansJP.ttf" \
  --unicodes='U+0000-024F,U+2000-206F,U+20A0-20CF,U+2190-21FF,U+25A0-25FF' \
  --output-file="$font_dir/noto-sans-ui-en.woff2" \
  --flavor=woff2 --layout-features='*' --no-hinting --desubroutinize

# Feed and comment copy is unbounded user content. Bundle the complete Hangul
# ranges separately so Korean posts remain readable even on systems without a
# CJK fallback font. The language-specific face is loaded only for lang="ko".
pyftsubset "$work_dir/NotoSansKR.ttf" \
  --unicodes='U+1100-11FF,U+3130-318F,U+A960-A97F,U+AC00-D7A3,U+D7B0-D7FF' \
  --output-file="$font_dir/noto-sans-content-ko.woff2" \
  --flavor=woff2 --layout-features='*' --no-hinting --desubroutinize

for pair in 'KR ko' 'JP ja' 'SC zh'; do
  read -r source target <<< "$pair"
  pyftsubset "$work_dir/NotoSans${source}.ttf" \
    --text-file="$copy_file" \
    --output-file="$font_dir/noto-sans-ui-${target}.woff2" \
    --flavor=woff2 --layout-features='*' --no-hinting --desubroutinize
done

curl --fail --silent --show-error --location \
  "https://raw.githubusercontent.com/undercasetype/Fraunces/$fraunces_revision/fonts/webfonts/Fraunces72pt-SemiBold.woff2" \
  --output "$font_dir/fraunces-72pt-semibold.woff2"
curl --fail --silent --show-error --location \
  "https://raw.githubusercontent.com/notofonts/noto-cjk/$noto_revision/Sans/LICENSE" \
  --output "$font_dir/OFL-Noto-Sans.txt"
curl --fail --silent --show-error --location \
  "https://raw.githubusercontent.com/undercasetype/Fraunces/$fraunces_revision/OFL.txt" \
  --output "$font_dir/OFL-Fraunces.txt"

chmod 0644 "$font_dir"/*
