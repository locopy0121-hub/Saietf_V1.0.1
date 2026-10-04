#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-app/build/outputs/apk/debug/app-debug.apk}"
AAPT="${ANDROID_HOME}/build-tools/36.0.0/aapt"
APKSIGNER="${ANDROID_HOME}/build-tools/36.0.0/apksigner"

EXPECTED_PACKAGE="tw.saietf.app"
EXPECTED_CERT_SHA256="A034087FD9D4DE4669715C518D244AE7A534676CC0AAB44B05BA0A8EE5E139B7"

BADGING="$("${AAPT}" dump badging "${APK_PATH}" | head -n 1)"
ACTUAL_PACKAGE="$(printf '%s\n' "${BADGING}" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
ACTUAL_VERSION_CODE="$(printf '%s\n' "${BADGING}" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
ACTUAL_CERT_SHA256="$("${APKSIGNER}" verify --print-certs "${APK_PATH}" | sed -n 's/Signer #1 certificate SHA-256 digest: //p' | tr '[:lower:]' '[:upper:]')"

[[ "${ACTUAL_PACKAGE}" == "${EXPECTED_PACKAGE}" ]]
[[ "${ACTUAL_CERT_SHA256}" == "${EXPECTED_CERT_SHA256}" ]]
[[ "${ACTUAL_VERSION_CODE}" =~ ^[0-9]+$ ]]

echo "UPGRADE_INSTALL_GATE=PASS package=${ACTUAL_PACKAGE} versionCode=${ACTUAL_VERSION_CODE}"
