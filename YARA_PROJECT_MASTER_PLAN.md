# YARA Project Master Plan
**Document status:** Integrated working baseline for team execution  
**Updated:** 2026-11-20  
**Repository:** `iman-shirin/yara-android`  
**Current verified source baseline:** `main` @ `b77624b2626883c528f3178d520c42d7a29f4ce4`  
**Product scope for this phase:** Android only

## 1. Mission and product outcome
Build YARA as a real, installable Persian-first Android AI assistant with a stable identity, provider-independent AI core, model-independent memory, voice interaction, a permissioned plugin system, and reliable everyday assistant workflows. The current delivery target is a tested Android APK and reproducible source package—not a mock-up or a feature catalog.

Windows desktop and Android-to-Windows connectivity remain future roadmap items. They are not part of the current execution sprint.

## 2. Source-of-truth and evidence rules
1. GitHub `main` is the only currently verified shared code baseline.
2. The reported 0.4.3 archive is a candidate source snapshot, not a Git commit, release, or accepted baseline.
3. A Git commit SHA is preferred but is not mandatory for a source archive that genuinely has no Git history. Such an archive may be considered only through a reproducible evidence package: original archive, independently verified archive SHA-256, complete path/hash manifest, extraction procedure, file-level hash reproduction, diff against a pinned main SHA, secret/license scan, and test/build evidence.
4. Every capability uses one of these states: Proposed → Code present → Unit-tested → Integration-tested → Device-verified → Release-ready. A later state requires evidence for every prior gate.
5. A report, screenshot, local path, or claimed hash is not independent verification. Preserve who supplied the evidence and what was actually reproduced.
6. Never force-push, commit directly to main, expose credentials, or promote unreviewed code.

## 3. Verified repository observations (2026-11-20)
- Default branch: `main`; repository permissions available to the project manager include admin/maintain/push.
- Main source version: `0.3.7`, versionCode `10`, package `com.yara.assistant`, minSdk 26, targetSdk 35, Java/Kotlin 17, ARM64 filter.
- Main README documents Persian Compose UI, Groq/Gemini-compatible chat, encrypted key storage, local conversation history, encrypted SQLite memory with lexical retrieval, reminders/tasks, DuckDuckGo search, Android speech recognition, Sherpa-ONNX/Piper TTS, and a VRM renderer. These are source/documentation claims; individual device behaviors still require device tests.
- Main CI workflow runs unit tests, checks a non-empty passing JUnit report, builds a debug APK, hashes it, and uploads an artifact.
- PR #20 governance branch has a successful Android workflow run #81 and artifact `Yara-Android-debug-81`; this validates the PR head build, not release readiness.
- PR #19 (plugin foundation) is open, non-draft, CI run #80 successful, no submitted reviews. Its diff adds a plugin catalog/manager and UI; it is not a complete plugin execution runtime.
- PR #18 (avatar identity specification) is open Draft, CI run #79 successful, no submitted reviews. The specification correctly states the generic CC0 VRM is temporary and not Yara's final identity.
- PR #5 (Groq/Gemini fallback) and PR #4 (Persian voice) are open; both target an older base SHA and GitHub currently reports them non-mergeable. Rebase/reconciliation and fresh CI are required before review.
- PR #20 is open Draft, currently mergeable, has no submitted reviews, and its documentation-only head has successful CI. It is not merged.

## 4. Product architecture — target logical modules
The implementation may remain a modular Android application initially; do not introduce unnecessary microservices or split modules before contracts and build value justify it.

### A. YARA Core
Owns application identity, conversation lifecycle, user settings, orchestration, state and lifecycle. It must not depend directly on a specific model vendor.

### B. AI Provider Layer and Model Router
Provider adapters: Groq, Gemini, OpenAI-compatible providers, and later local engines. A stable provider contract exposes capabilities, context limits, streaming, structured output, timeout, cancellation, typed errors, availability, rate limits, and privacy/data-handling metadata.

Routing sequence:
1. Classify task and sensitivity (multi-label task profile).
2. Apply hard privacy, user-policy, and capability eligibility gates.
3. Score only eligible providers by task-specific quality, reliability/health, latency and cost.
4. Execute with request tracking, bounded retry and circuit breaker.
5. Fallback only for classified retryable failures; authentication, policy, invalid request, and consent failures must not be disguised as network errors.
6. If no eligible provider exists, stop or request explicit scoped consent. Never silently downgrade privacy.

Privacy is a hard eligibility rule, not merely a weighted score. The proposed Router v2 weight `privacy=0.35` must not override this rule.

### C. Memory System
All memory access goes through `IMemoryRepository` (or an equivalent stable repository boundary). Keep the existing encrypted storage working while migrating incrementally.

Logical stores:
- Short-term conversation context
- Episodic conversation/history
- Semantic user facts
- User documents/RAG

Every memory item needs provenance/source, timestamps, confidence, sensitivity, retention/deletion behavior and conflict handling. Delete/update must propagate to indexes and derived stores. Confidence alone is not sufficient to persist a fact. Vector search and embeddings require benchmark and migration design before adoption.

### D. Agent and Tool Orchestration
A bounded planner/executor with explicit tool schemas, typed results, timeout/cancellation, idempotency and loop detection. Enforce iteration, elapsed-time, tool-call and resource budgets. Model output is untrusted input and never grants tool authority.

### E. Permission, Consent and Audit Gateway
Every tool invocation is authorized at execution time. Consent is a durable request with unique ID, exact action/parameters, scope, expiry and status. Timeout means deny. Parameter changes invalidate prior approval. High-impact operations require explicit approval. Audit events should be append-oriented and tamper-evident; logs must avoid secrets and unnecessary personal data.

### F. Plugin Runtime
Keep the catalog distinct from executable plugins. A plugin is implemented only when it has lifecycle, invocation contract, permissions, dependency/capability checks, persisted state, error handling, observability and tests. Catalog toggles alone are not runtime functionality. Third-party connectors require OAuth/credential isolation and per-scope consent.

### G. Android UI and Avatar
Use Compose with staged ViewModel/UDF separation. Keep business logic out of `MainActivity` over time without a risky rewrite. Avatar states derive from public assistant states (idle, listening, thinking, speaking, waiting-for-approval, error). The final Yara model must follow the approved identity references; generic VRM is temporary. Lip-sync target is viseme/phoneme-driven; amplitude-only movement is an interim fallback.

### H. Voice and Assistant Workflows
Persian STT/TTS must expose availability and error states; do not promise offline recognition unless tested. Test audio focus, lifecycle, interruption, cancellation and avatar synchronization. Reminders/tasks/search must remain usable when model connectivity fails where appropriate.

### I. Observability, Data and Recovery
Structured diagnostics, redacted logs, crash handling, database migrations, export/deletion behavior, backup policy, and rollback/recovery procedures are part of the product—not post-release extras.

## 5. Team operating model
| Team member | Accountable area | Deliverable |
|---|---|---|
| ChatGPT — Project Manager / Integrator | Roadmap, architecture integration, prioritization, repository coordination, acceptance gates | Master plan, ADRs, integrated review and release decision |
| Claude — Architecture Lead | Memory, Agent/Tool contracts, consent, audit, provider contract alignment | Kotlin-ready contracts, threat model, failure modes and test specifications |
| Grok — Model Research Lead | Router v2, provider API research, local-model feasibility and reproducible benchmarks | Complete Router spec, evidence-backed provider matrix and resource benchmark |
| Gemini — Independent QA/Security | Independent review of source snapshot, router, Android security, WebView/JS bridge, plugin permissions and release evidence | Findings by severity with paths, evidence, exploit/failure scenario and acceptance criteria |
| GitHub Copilot — Implementation | Scoped implementation and automated tests after contracts are accepted | Small PRs, focused tests, build evidence and migration/rollback notes |
| Repository owner | Final authority for sensitive policy, material scope changes and public release | Approval where risk or release impact requires it |

No external model is assumed to have GitHub access merely because it is part of the team. All code changes must be visible as branches/PRs in the authorized repository.

## 6. Execution roadmap and gates

### P0 — Establish a reproducible source candidate
Owner: ChatGPT; evidence preparation: Grok; independent verification: Gemini.
- Obtain the actual 0.4.3 archive and full Router v2 document in a shared review location.
- Reproduce archive SHA-256 and all manifest file hashes after safe extraction.
- Inspect archive paths, `local.properties`, secrets, signing material, licenses and unexpected binaries.
- Compare all paths and content against pinned main; classify added/modified/deleted files.
- Reconcile the archive provenance without inventing a Git commit SHA.
Gate: signed-off evidence report; no code import before this gate.

### P1 — Finalize implementation contracts
Owner: Claude; review: ChatGPT and Gemini; Router research: Grok.
- Provider/TaskProfile/RoutingDecision contracts
- Memory repository, provenance, deletion and migration contracts
- Tool schema, permission/consent lifecycle and audit event contracts
- Plugin lifecycle and persisted state contract
- UI state and avatar state contract
Gate: interfaces, failure modes, threat cases and acceptance tests are implementation-ready.

### P2 — Reconcile existing PRs and branch history
Owner: ChatGPT; reviewers: Claude/Gemini.
- Review PR #20 governance docs and update archive acceptance language.
- Review PR #19 plugin catalog/manager and identify runtime gaps.
- Review PR #18 avatar identity specification and asset privacy/licensing.
- Rebase/reconcile PR #4 and #5 only after inspecting their full diffs and current main ancestry.
- Inspect all other feature branches before selecting changes; do not merge by branch name or age.
Gate: one approved integration sequence with fresh CI for each candidate PR.

### P3 — Implement in small, testable increments
Recommended order:
1. Establish a reproducible baseline and stabilize current app behavior.
2. Extract ViewModel/UDF boundaries incrementally.
3. Introduce `IMemoryRepository` over existing encrypted store.
4. Implement permission/consent/audit enforcement before expanding tool access.
5. Finalize provider adapter and privacy-gated router without breaking existing chat API.
6. Upgrade plugin catalog into a real permissioned runtime.
7. Add semantic retrieval only after quality, storage, privacy and migration benchmarks.
8. Integrate final Yara avatar asset and viseme pipeline after asset/license/device validation.
9. Add local model adapters only after Android resource benchmarking.

### P4 — Release candidate
- Clean source checkout at exact commit
- Passing CI, unit and integration tests with report
- Security and privacy review
- Signed APK/AAB from protected signing configuration
- SHA-256 and artifact provenance
- Clean install and upgrade test on physical Android device(s)
- Persian voice, avatar, model fallback, memory, plugin and offline/failure-path tests
- Rollback/recovery notes
- Owner approval

## 7. Definition of Done for every PR
Each PR must include:
- problem and scope; owner; base SHA; exact changed paths
- architecture/ADR reference
- security/privacy impact and permissions
- unit/integration tests with results
- successful `:app:testDebugUnitTest` and `:app:assembleDebug` (or documented justified alternative)
- artifact/checksum when build output is relevant
- device evidence when UI, voice, avatar, permissions or lifecycle behavior changes
- migration, compatibility and rollback notes
- known limitations and explicit status of each capability

## 8. Branch and merge policy
- Use `feature/<scope>`, `fix/<scope>`, `docs/<scope>`, or `governance/<scope>`.
- No direct commits to `main`; no force-push.
- One coherent scope per PR; avoid bundling unrelated features.
- Require passing CI and at least one independent technical review; security-sensitive changes also require security review.
- Re-run CI after rebase or material changes.
- Do not merge merely because GitHub reports `mergeable=true`.
- Never commit API keys, OAuth tokens, signing keys, private reference images without authorization, or `local.properties`.

## 9. Immediate team handoffs
- Grok: deliver the archive and Router v2 file as downloadable attachments, not only local paths; include archive provenance and exact extraction command. Do not create branches or push code yet.
- Gemini: prepare an independent audit checklist now; execute code-level review after the archive is available. Review PR #19 and the Android WebView/JavaScript bridge in particular.
- Claude: return implementation-ready Kotlin contracts for memory, provider/router, Agent/Tool, consent/audit and plugin runtime; explicitly reconcile with Router v2.
- Copilot: do not start broad implementation until P0/P1 gates pass. Prepare isolated testable PRs only after scoped assignment.
- ChatGPT: maintain the decision register, verify all evidence, coordinate PR sequence and publish status updates.

## 10. Current decision
The project direction is approved for coordinated Android-first development. The 0.4.3 snapshot, Router v2 implementation, plugin runtime completeness, final Yara avatar, and release readiness are **not yet independently approved**. No code from the 0.4.3 archive is authorized for import until P0 passes.
