# SaiETF development APK signing

SaiETF V1.0.4 establishes a repository-fixed **development-only** signing identity for installable CI APKs.

- Package: `tw.saietf.app`
- First fixed-signing version: `1.0.4`
- Certificate SHA-256: `A034087FD9D4DE4669715C518D244AE7A534676CC0AAB44B05BA0A8EE5E139B7`
- CI verifies the APK certificate before publishing the artifact.
- This key is intentionally development-only and must never be reused for a production/store release.

V1.0.3 did not publish an APK because its CI compile gate failed. Because V1.0.1 and V1.0.2 were built with ephemeral runner debug keys, V1.0.2 cannot be upgraded in-place to V1.0.4. A one-time uninstall is required when moving to V1.0.4. From V1.0.4 onward, CI APKs using this fixed development identity can upgrade in place without deleting Room data.
