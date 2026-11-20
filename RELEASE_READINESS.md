# YARA Release Readiness

**Last updated:** 2026-11-20  
**Current verified GitHub main version:** `0.3.7` (`versionCode 10`, per `app/build.gradle.kts`)  
**Release decision:** **NOT APPROVED** — checklist evidence is incomplete.

## Evidence checklist

| Gate | Required evidence | Current status |
|---|---|---|
| Source baseline | Tag/commit SHA and clean source state | Partial: main is baseline; release tag not verified |
| Debug build | CI run linked to exact candidate SHA | Re-run required for candidate |
| Unit tests | Full test report linked to candidate SHA | Incomplete |
| Integration tests | Provider, database, migration, plugin and tool tests | Not established |
| Security | Secret handling, permissions, WebView, backup and audit review | Review required |
| Release signing | Signed APK/AAB and signing configuration kept outside repository | Not established |
| Artifact integrity | SHA-256 and provenance | Not established for a release candidate |
| Install/upgrade | Clean install and upgrade from previous supported version | Not established |
| Physical device | Device model, Android version, test cases and results | Not established |
| Voice/avatar | Persian STT/TTS, audio lifecycle, avatar and fallback tests | Not established |
| Privacy | Data export/deletion, backup policy and consent behavior | Review required |
| Recovery | Crash recovery, migration rollback and release rollback plan | Not established |

## Mandatory release evidence

A release candidate must provide:
1. Exact source commit SHA and version metadata.
2. Passing CI for that SHA.
3. Test summary including failures/skips.
4. Signed artifact stored in an approved release location (never commit signing keys).
5. SHA-256 checksum and artifact provenance.
6. Installation and upgrade test results on named physical devices.
7. Security review and unresolved-risk register.
8. Owner approval.

## Status vocabulary

- **Not started:** no evidence.
- **In progress:** work underway, evidence incomplete.
- **Blocked:** dependency or failure prevents progress.
- **Passed:** required evidence reviewed and accepted.
- **Waived:** explicit owner approval with documented risk.

A blank or unknown result is not a pass.
