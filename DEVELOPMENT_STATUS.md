# YARA Development Status

**Last updated:** 2026-11-20  
**Repository:** `iman-shirin/yara-android`  
**Baseline branch:** `main`  
**Coordination branch:** `governance/team-coordination-2026-11-20`

## Source-of-truth policy

- Until a newer source snapshot is verified and reviewed, the GitHub `main` branch is the only verified shared baseline.
- The reported local snapshot `0.4.3` / `versionCode 16` from Grok is **unverified against a commit SHA**. Do not treat it as merged, released, or authoritative.
- Never overwrite `main`, force-push, or import an untraceable local directory.
- Every capability status must distinguish: **Proposed**, **Code present**, **Unit-tested**, **Integration-tested**, **Device-verified**, and **Release-ready**.

## Verified baseline observations

The following were checked against GitHub `main` on 2026-11-20:

| Area | Verified observation | Status |
|---|---|---|
| Android app | `app/build.gradle.kts` reports `versionName 0.3.7`, `versionCode 10`, minSdk 26, targetSdk 35, Java/Kotlin 17 | Verified in source |
| Package | Namespace/application ID `com.yara.assistant` | Verified in source |
| Provider layer | Earlier source audit found Groq and Gemini-compatible provider support with limited fallback | Needs fresh code-level revalidation before release claims |
| Memory | Earlier source audit found encrypted SQLite storage and lexical retrieval | Needs fresh code-level revalidation |
| Plugins | PR #19 previously introduced a preliminary plugin catalog/manager; it was not a complete runtime | PR review required |
| UI | Earlier source audit found Compose UI with substantial logic in `MainActivity.kt` | Needs fresh code-level revalidation |
| Build | Prior CI evidence showed successful debug builds on earlier refs; this does not prove current main or device readiness | Re-run CI |
| Release | Signed release APK and physical-device verification not established | Not release-ready |

## Unverified report: local 0.4.3

Grok reported a local tree at `/home/workdir/artifacts/yara-android-main` with `versionCode 16`, additional plugins, audio-viseme code and documentation. These claims remain **reported, not independently verified**.

Required evidence before adopting that snapshot:
1. Full 40-character commit SHA and originating remote/ref.
2. `git status --short` and `git log -n 10 --oneline --decorate`.
3. Diff/stat against current `main`, including added, modified and deleted paths.
4. Test command and complete test summary.
5. Build command, artifact metadata and SHA-256.
6. Confirmation that no secrets, signing keys, generated credentials or private user data are included.

## Current blockers / risks

- Establish whether the reported 0.4.3 tree is based on a reachable Git commit.
- Reconcile branch history before any code promotion.
- Complete a real plugin runtime and persistent enablement state; UI toggles alone are insufficient.
- Replace lexical-only memory incrementally with repository abstraction and evaluated semantic retrieval.
- Introduce enforceable tool permission checks and durable consent state.
- Reduce UI/business-logic coupling through ViewModel and use-case boundaries.
- Expand integration, migration, security and device tests.
- Establish signed release build and physical-device validation.

## Status update protocol

Each owner updates this file through a pull request and includes:
- commit/PR reference;
- exact paths changed;
- tests run and results;
- known limitations;
- evidence for any status promotion.

Do not mark a feature **Device-verified** without a device/model/OS record and reproducible test evidence. Do not mark **Release-ready** without a signed artifact, checksum, install/upgrade test, and release checklist completion.
