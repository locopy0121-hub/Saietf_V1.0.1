#!/usr/bin/env bash
set -euo pipefail

VERSION_FILE="${1:-app/version.properties}"
GRADLE_FILE="${2:-app/build.gradle.kts}"
BUILD_CONTRACT_TEST="${3:-app/src/test/kotlin/tw/saietf/app/BuildContractTest.kt}"

property() {
  local key="$1"
  sed -n "s/^${key}=//p" "${VERSION_FILE}" | tail -n 1
}

VERSION_NAME="$(property versionName)"
VERSION_CODE="$(property versionCode)"

[[ "${VERSION_NAME}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "release-version: invalid versionName: ${VERSION_NAME}" >&2
  exit 1
}
[[ "${VERSION_CODE}" =~ ^[0-9]+$ ]] || {
  echo "release-version: invalid versionCode: ${VERSION_CODE}" >&2
  exit 1
}

sed -i -E "s/versionCode = [0-9]+/versionCode = ${VERSION_CODE}/" "${GRADLE_FILE}"
sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"${VERSION_NAME}\"/" "${GRADLE_FILE}"

if [ -f "${BUILD_CONTRACT_TEST}" ]; then
  python3 - "${BUILD_CONTRACT_TEST}" "${VERSION_NAME}" "${VERSION_CODE}" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
version_name = sys.argv[2]
version_code = sys.argv[3]
text = path.read_text()
text, name_count = re.subn(
    r'assertEquals\("[0-9]+\.[0-9]+\.[0-9]+", BuildConfig\.VERSION_NAME\)',
    f'assertEquals("{version_name}", BuildConfig.VERSION_NAME)',
    text,
    count=1,
)
text, code_count = re.subn(
    r'assertEquals\([0-9]+, BuildConfig\.VERSION_CODE\)',
    f'assertEquals({version_code}, BuildConfig.VERSION_CODE)',
    text,
    count=1,
)
if name_count != 1 or code_count != 1:
    raise SystemExit(
        f'release-version: failed to synchronize BuildContractTest '
        f'(name={name_count}, code={code_count})'
    )
path.write_text(text)
PY
fi

grep -q "versionCode = ${VERSION_CODE}" "${GRADLE_FILE}"
grep -q "versionName = \"${VERSION_NAME}\"" "${GRADLE_FILE}"
if [ -f "${BUILD_CONTRACT_TEST}" ]; then
  grep -Fq "assertEquals(\"${VERSION_NAME}\", BuildConfig.VERSION_NAME)" "${BUILD_CONTRACT_TEST}"
  grep -Fq "assertEquals(${VERSION_CODE}, BuildConfig.VERSION_CODE)" "${BUILD_CONTRACT_TEST}"
fi

echo "SAIETF_VERSION_NAME=${VERSION_NAME}"
echo "SAIETF_VERSION_CODE=${VERSION_CODE}"
