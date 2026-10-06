#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

THEMED_RUNTIME_FILES=(
  "app/src/main/kotlin/tw/saietf/app/MainActivity.kt"
  "app/src/main/kotlin/tw/saietf/app/TaiwanKLineView.kt"
  "app/src/main/kotlin/tw/saietf/app/PortfolioTrendView.kt"
)

for path in "${THEMED_RUNTIME_FILES[@]}"; do
  if grep -Eq 'Color\.(rgb|WHITE|BLACK)' "$path"; then
    echo "THEME_CONTRACT_FAIL: light-only hardcoded color found in $path" >&2
    grep -En 'Color\.(rgb|WHITE|BLACK)' "$path" >&2 || true
    exit 1
  fi
done

THEME_FILE="app/src/main/kotlin/tw/saietf/app/SaiTheme.kt"
STYLES_FILE="app/src/main/res/values/styles.xml"
ACTIVITY_FILE="app/src/main/kotlin/tw/saietf/app/MainActivity.kt"

grep -F 'SYSTEM("system")' "$THEME_FILE" >/dev/null
grep -F 'LIGHT("light")' "$THEME_FILE" >/dev/null
grep -F 'DARK("dark")' "$THEME_FILE" >/dev/null
grep -F 'fun applyDarkMode(enabled: Boolean)' "$THEME_FILE" >/dev/null
grep -F 'Theme.SaiETF.Dark' "$STYLES_FILE" >/dev/null
test "$(grep -c 'android:forceDarkAllowed">false' "$STYLES_FILE")" -eq 2
grep -F 'showThemeSettingsDialog' "$ACTIVITY_FILE" >/dev/null
grep -F 'toggleDayNightTheme' "$ACTIVITY_FILE" >/dev/null
grep -F 'setTheme(if (darkTheme) R.style.Theme_SaiETF_Dark else R.style.Theme_SaiETF)' "$ACTIVITY_FILE" >/dev/null

echo "SAIETF_DAY_NIGHT_THEME_CONTRACT=PASS"
