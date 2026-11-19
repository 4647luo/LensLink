# Changelog

## 3.26 baseline

- Nikon PTP/IP support for camera AP, phone-hotspot STA, and shared-router modes.
- Live-view frame retrieval, throttled decode queue, and live transport metrics.
- Optional new-photo detection and JPEG retrieval.
- ZIP export for bounded connection diagnostics.
- Full-screen preview and existing LUT, frame, watermark, USB PTP, and SD import workflows.

This is a summary of code paths present in the baseline, not a claim that every feature works on every Nikon body. Known limitations and the Z 30 validation scope are recorded in `README.md`.

## Open-source preparation

- Added Apache-2.0 license and third-party/protocol research notices.
- Removed a machine-specific JDK path, pinned the official Gradle 8.7 distribution checksum, and strengthened ignored-file rules.
- Excluded private connection traces and staged photo files from Android backups while preserving restorable pairing preferences.
- Added project build, privacy, architecture, contribution, and security documentation.
- Added a release checklist that identifies remaining ownership, dependency-notice, build, and device-validation steps.
