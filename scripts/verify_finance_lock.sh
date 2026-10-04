#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

EXPECTED_COMMIT="a872644c572d24fc5ffe597a97eb6d7d8bd7283f"
MANIFEST="core/finance/src/test/resources/v378/manifest.json"

grep -F "\"sourceCommit\": \"$EXPECTED_COMMIT\"" "$MANIFEST" >/dev/null

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

node tools/tf-v378/export-fixtures.mjs "$TMP_DIR"
diff -ru core/finance/src/test/resources/v378 "$TMP_DIR"

./gradlew --no-daemon :core:finance:test \
  --tests '*FixtureIntegrityTest' \
  --tests '*FinanceCoreLockTest' \
  --tests '*FinanceEngineGoldenTest' \
  --tests '*LedgerProjectorGoldenTest' \
  --tests '*PortfolioAggregatorTest'
