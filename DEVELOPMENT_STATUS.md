# YARA Development Status

**Last updated:** 2026-11-20  
**Repository:** `iman-shirin/yara-android`  
**Verified code baseline:** `main` @ `b77624b2626883c528f3178d520c42d7a29f4ce4`  
**Coordination branch:** `governance/team-coordination-2026-11-20`  
**Current execution scope:** Android only

## Source-of-truth policy
- GitHub `main` is the only currently verified shared code baseline.
- The reported local source archive `yara-android-0.4.3-snapshot-verified.tar.gz` is a candidate snapshot, not a Git commit, merged branch, release, or accepted baseline.
- A source archive without Git history is not automatically rejected for lacking a commit SHA. It must pass archive-based provenance verification: original bytes, independently reproduced archive SHA-256, complete manifest, safe extraction, independently reproduced file hashes, pinned-main diff, secret/license review, and reproducible test/build evidence.
- The archive SHA-256 and 67-file manifest were supplied in the project conversation but have **not yet been independently reproduced from the actual archive** in this environment. The binary archive and complete Router v2 document must be uploaded to the shared review context.
- Do not overwrite main, force-push, or import an unverified local directory.
- Capability status must distinguish: Proposed, Code present, Unit-tested, Integration-tested, Device-verified, Release-ready.

## Verified main observations (2026-11-20)
| Area | Verified observation | Status |
|---|---|---|
| Android app | `app/build.gradle.kts`: versionName 0.3.7, versionCode 10, minSdk 26, targetSdk 35, Java/Kotlin 17, ARM64 filter | Verified in source |
| Package | `com.yara.assistant` | Verified in source |
| README capability claims | Persian Compose UI, Groq/Gemini-compatible chat, encrypted key storage, local history, encrypted SQLite memory with lexical retrieval, reminders/tasks, DuckDuckGo search, Android STT, Sherpa-ONNX/Piper TTS and VRM renderer | Source-documented; device behavior still needs tests |
| CI workflow | Unit tests, non-empty passing JUnit report, debug APK build, SHA-256 and artifact upload | Verified in workflow source |
| Release | Signed production APK/AAB and physical-device evidence not established | Not release-ready |

## Current GitHub PR and branch observations
| Item | Observed state | Required action |
|---|---|---|
| PR #20 — governance | Open Draft; mergeable=true; no reviews; Actions run #81 success; artifact `Yara-Android-debug-81` exists | Complete documentation review and obtain independent review before merge |
| PR #19 — plugin foundation | Open, non-draft; mergeable=true; no reviews; Actions run #80 success; 2 files changed | Review catalog/manager; do not call it a complete plugin runtime |
| PR #18 — avatar identity spec | Open Draft; mergeable=true; no reviews; Actions run #79 success; 1 file changed | Review identity criteria and reference-image privacy/licensing |
| PR #5 — Groq/Gemini fallback | Open Draft; older base SHA; mergeable=false | Inspect/reconcile ancestry and diff; rebase only in scoped branch; rerun CI |
| PR #4 — Persian voice | Open, non-draft; older base SHA; mergeable=false | Inspect/reconcile ancestry and diff; rebase only in scoped branch; rerun CI |

The repository contains multiple feature/fix/integration branches. Branch existence is not proof of merge or functionality. Inspect ancestry and PR diff before selecting code.

## Reported 0.4.3 snapshot — not independently verified
Grok supplied:
- archive name: `yara-android-0.4.3-snapshot-verified.tar.gz`
- declared archive SHA-256: `9d1551507dffd40ac6287609595e0f943191ed08f420a98f01bc58414768a2aa`
- declared manifest: 67 files; manifest SHA-256: `b268bdd689f366ae3d2d0c990c6b4ad10d5d1105c1d1514f5f6c7eafa8a9d1ea`
- reported source version: 0.4.3 / versionCode 16
- reported omissions from archive: `.git`, `.gradle`, `.kotlin`, `app/build`, `node_modules`
- reported test/build status: six test files present; tests not executed in that environment; no debug APK, signed release APK, or device verification.

These are **reported values only** until the archive is uploaded and independently checked. No originating Git commit SHA is available according to the evidence owner. Archive-based verification is acceptable if all reproducibility and provenance gates above pass.

Specific inspection risks:
- `local.properties` appears in the supplied manifest; inspect it and exclude it from repository promotion.
- Two VRM paths have the same declared SHA-256; treat them as identical content until verified otherwise.
- Review large binary assets and Sherpa AAR for provenance, license, integrity and secrets.
- The complete Router v2.1 text has not yet been supplied in this shared review context; the summary alone is insufficient for architecture/security approval.

## Current blockers
1. Obtain the actual 0.4.3 archive and full Router v2.1 document.
2. Independently reproduce archive and manifest hashes and file-level hashes.
3. Review secrets, local configuration, binary provenance and licenses.
4. Reconcile PRs/branches against the pinned main baseline.
5. Finalize provider, memory, Agent/Tool, consent/audit and plugin contracts.
6. Establish integration, migration, security and physical-device tests.
7. Produce signed release artifact and install/upgrade evidence.

## Status update protocol
Each owner updates this file through a PR and includes commit/PR reference, exact paths, tests run and results, known limitations, risks, and evidence for every status promotion. A source claim or successful CI build does not prove device verification or release readiness.
