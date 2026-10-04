#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-app/build/outputs/apk/debug/app-debug.apk}"
CONTRACT_PATH="${2:-app/upgrade-install-contract.properties}"
AAPT="${ANDROID_HOME}/build-tools/36.0.0/aapt"
APKSIGNER="${ANDROID_HOME}/build-tools/36.0.0/apksigner"

if [[ ! -f "${APK_PATH}" ]]; then
  echo "upgrade-gate: APK not found: ${APK_PATH}" >&2
  exit 1
fi

if [[ ! -f "${CONTRACT_PATH}" ]]; then
  echo "upgrade-gate: contract not found: ${CONTRACT_PATH}" >&2
  exit 1
fi

property() {
  local key="$1"
  sed -n "s/^${key}=//p" "${CONTRACT_PATH}" | tail -n 1
}

EXPECTED_PACKAGE="$(property packageName)"
EXPECTED_CERT_SHA256="$(property certificateSha256 | tr '[:lower:]' '[:upper:]')"
PREVIOUS_VERSION_CODE="$(property previousVerifiedVersionCode)"

BADGING="$("${AAPT}" dump badging "${APK_PATH}" | head -n 1)"
ACTUAL_PACKAGE="$(printf '%s\n' "${BADGING}" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
ACTUAL_VERSION_CODE="$(printf '%s\n' "${BADGING}" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
ACTUAL_CERT_SHA256="$("${APKSIGNER}" verify --print-certs "${APK_PATH}" | sed -n 's/Signer #1 certificate SHA-256 digest: //p' | tr '[:lower:]' '[:upper:]')"

echo "upgrade-gate package=${ACTUAL_PACKAGE}"
echo "upgrade-gate versionCode=${ACTUAL_VERSION_CODE} previous=${PREVIOUS_VERSION_CODE}"
echo "upgrade-gate certificateSHA256=${ACTUAL_CERT_SHA256}"

[[ "${ACTUAL_PACKAGE}" == "${EXPECTED_PACKAGE}" ]] || {
  echo "upgrade-gate: package identity changed; Android update would fail" >&2
  exit 1
}

[[ "${ACTUAL_CERT_SHA256}" == "${EXPECTED_CERT_SHA256}" ]] || {
  echo "upgrade-gate: signing identity changed; Android update would fail" >&2
  exit 1
}

[[ "${ACTUAL_VERSION_CODE}" =~ ^[0-9]+$ && "${PREVIOUS_VERSION_CODE}" =~ ^[0-9]+$ ]] || {
  echo "upgrade-gate: invalid versionCode contract" >&2
  exit 1
}

(( ACTUAL_VERSION_CODE > PREVIOUS_VERSION_CODE )) || {
  echo "upgrade-gate: versionCode must increase for in-place upgrade" >&2
  exit 1
}

echo "UPGRADE_INSTALL_GATE=PASS"
echo "APK is compatible with the fixed-signing upgrade line."
