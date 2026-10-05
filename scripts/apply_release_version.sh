#!/usr/bin/env bash
set -euo pipefail

VERSION_FILE="${1:-app/version.properties}"
GRADLE_FILE="${2:-app/build.gradle.kts}"

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

grep -q "versionCode = ${VERSION_CODE}" "${GRADLE_FILE}"
grep -q "versionName = \"${VERSION_NAME}\"" "${GRADLE_FILE}"

echo "SAIETF_VERSION_NAME=${VERSION_NAME}"
echo "SAIETF_VERSION_CODE=${VERSION_CODE}"
