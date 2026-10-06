#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

check_blob() {
  local expected="$1"
  local path="$2"
  local actual
  actual="$(git hash-object "$path")"
  if [ "$actual" != "$expected" ]; then
    echo "MARKET_CORE_LOCK_MISMATCH: $path" >&2
    echo "expected=$expected" >&2
    echo "actual=$actual" >&2
    exit 1
  fi
  echo "MARKET_CORE_LOCK_OK $path $actual"
}

check_blob "546cf2312bcca705597908a42b18f6ccf8f9e5ce" "core/market/src/main/kotlin/tw/saietf/core/market/MarketDataCenter.kt"
check_blob "920a6bb0f0ef9c69f60989a445e5eba0d6a2b3c8" "core/market/src/main/kotlin/tw/saietf/core/market/MarketArbitrator.kt"
check_blob "90339caf990ebd86873d8e22073874dfe0fbbd29" "core/market/src/main/kotlin/tw/saietf/core/market/MarketDataProviderV2.kt"
check_blob "aa6e5aa1d33022e8aee074b277f73a5308f634a2" "core/market/src/main/kotlin/tw/saietf/core/market/MarketModels.kt"
check_blob "c86de4d2bbd7a032880ecddecff5a57eca0bd418" "core/market/src/main/kotlin/tw/saietf/core/market/MemoryMarketStore.kt"
check_blob "a9a022f8e17938be83efa2db4e265ab5ff3b4578" "core/market/src/main/kotlin/tw/saietf/core/market/ProviderCircuitBreaker.kt"

./gradlew --no-daemon :core:market:test

echo "MARKET_UPDATE_CENTER_CORE_LOCK=PASS"
