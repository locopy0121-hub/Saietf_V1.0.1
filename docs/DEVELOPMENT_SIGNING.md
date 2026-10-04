# SaiETF development APK signing and upgrade contract

SaiETF uses one fixed **development-only** signing identity for CI APKs so Android can perform normal in-place upgrades without uninstalling the previous app.

- Package: `tw.saietf.app`
- First published fixed-signing APK: `1.0.4` (`versionCode=10004`)
- Current upgrade contract epoch: `1`
- Certificate SHA-256: `A034087FD9D4DE4669715C518D244AE7A534676CC0AAB44B05BA0A8EE5E139B7`
- Every installable CI APK must use this exact package and certificate.
- Every new APK must have a higher `versionCode`.
- CI runs `scripts/verify_upgrade_install_contract.sh` after APK creation. Artifact upload is blocked if package, versionCode, or certificate breaks update compatibility.
- The development key is not a production/store signing identity and must never be reused for a production release.

## Legacy boundary

V1.0.1 and V1.0.2 were created by ephemeral GitHub runners before a persistent signing identity existed. Their private signing keys were not stored in the repository or CI configuration, so Android cryptographically rejects an in-place update from those legacy APKs to the fixed-signing line.

That legacy boundary is not repeated. Once a device is on a fixed-signing build (V1.0.4 or later), subsequent CI APKs must install by normal Android update/replace and preserve the Room database. A build that would require another uninstall must fail CI and must not be published.
