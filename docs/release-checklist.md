# Release checklist

This checklist records items that still require the maintainer's own confirmation before publishing a public repository or binary.

- [ ] Confirm authority to license every source file, icon, sample, and other asset under Apache-2.0. This license file cannot grant rights to material the publisher does not own or have permission to relicense.
- [ ] Review any code or assets copied/adapted from AI tools, community projects, protocol notes, or earlier private app versions. If source code or fixtures from `open-nikonz/zf-capture-one-helper` were copied, retain the applicable copyright/attribution notices and include its Apache-2.0 license; this source tree currently contains only a research-reference note.
- [ ] Generate a resolved dependency/SBOM report from a clean online checkout and confirm notices for all runtime and test dependencies. `THIRD_PARTY_NOTICES.md` is a direct-dependency summary, not a complete SBOM.
- [ ] Confirm notices are available with any separately distributed APK. The current Gradle packaging rules omit some `META-INF` license resources, so the repository notice file alone may not accompany a standalone APK.
- [ ] Build from a clean checkout using JDK 17+ and Android SDK 34, then run the existing unit tests. This open-source preparation pass did not run a Gradle build or tests because the Gradle distribution was unavailable offline.
- [ ] Record the existing owner-reported Z 6III, Z 30 and Z 6 evidence by firmware, connection mode and feature. Verify unresolved auto-JPG and SD-card access separately, and document additional models before claiming support.
- [ ] Remove or redact any personal diagnostic ZIP, phone/camera identifiers, local IPs, photos, signing keys, and machine-specific settings from the repository and release artifacts.
- [ ] Add the repository URL and maintainer's private security-reporting contact after choosing a hosting service; enable private vulnerability reporting if supported.

The checklist is a release hygiene aid, not legal advice or a guarantee of compatibility.
