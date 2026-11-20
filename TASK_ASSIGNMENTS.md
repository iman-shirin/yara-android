# YARA Task Assignments

**Last updated:** 2026-11-20  
**Coordination branch:** `governance/team-coordination-2026-11-20`  
**Current execution scope:** Android only

## Team responsibilities and authority
| Member | Primary responsibility | Deliverables | Boundaries |
|---|---|---|---|
| ChatGPT — Project Manager / Integrator | Roadmap, architecture integration, prioritization, repository coordination and quality gates | Master plan, ADRs, integrated review, PR sequence, release decision | Report only verified repository/tool evidence; no unreviewed main changes |
| Claude — Architecture Lead | Semantic Memory, Memory Orchestrator, Agent Framework, provider contracts, tool security | Kotlin-ready contracts, threat scenarios, failure modes, test specifications, Router alignment | Design/review first; implementation only on scoped assignment |
| Grok — Model Research Lead | Provider API research, Model Router v2, local-model feasibility and benchmarks, 0.4.3 evidence package | Complete Router v2.1 file, reproducible provider matrix/benchmarks, archive provenance | No direct main changes; no branch/push without scoped authorization; local paths alone are not deliverables |
| Gemini — Independent QA/Security | Independent architecture, source, Android security and release-readiness audit | `YARA-INDEPENDENT-AUDIT-*.md` with paths, evidence, severity, attack/failure scenarios and acceptance criteria | Independent reviewer; no implied GitHub write access |
| GitHub Copilot — Implementation | Approved, bounded implementation and automated tests | Small feature/fix branches, tests, PRs, build evidence | Wait for accepted contracts and scoped tickets; no direct commits to main |
| Repository owner | Final authority on sensitive policy, material scope changes and public release | Explicit approvals where required | API keys, signing keys, private data and public release remain protected |

## P0 — Reproducible source baseline
**Coordinator:** ChatGPT  
**Evidence preparation:** Grok  
**Independent verification:** Gemini

Grok must provide the actual archive as an attachment/downloadable artifact, not only a path in another environment, plus:
- exact archive SHA-256 and extraction command/tool/version;
- complete 67-file manifest and its exact bytes;
- source of the archive and a truthful statement that no Git history exists;
- any build/test logs and the exact environment used.

ChatGPT/Gemini must:
- reproduce archive SHA-256 and file-level hashes;
- safely inspect archive paths, symlinks, `local.properties`, credentials, signing material, user data and licenses;
- compare all added/modified/deleted files against pinned main;
- inspect critical Kotlin, Gradle, manifest, WebView/JS bridge and avatar files;
- report what was actually verified versus merely reported.

**Acceptance:** reproducible archive evidence and independent audit. No code import before approval.

## P1 — Architecture contracts
**Owner:** Claude  
**Review:** ChatGPT + Gemini

Deliver implementation-ready contracts for:
- `IMemoryRepository`, provenance, confidence, sensitivity, conflict/deletion propagation and migrations;
- provider interface, TaskProfile, RoutingDecision, capability matching, typed errors, timeout/cancellation and streaming;
- Agent/Tool invocation, schemas, idempotency, loop detection and budgets;
- durable consent request lifecycle, parameter binding, expiry-deny and audit event schema;
- plugin lifecycle, invocation, permissions, dependency checks, persistence and failure handling;
- Android UI state and public avatar state.

**Acceptance:** internally consistent interfaces, failure modes, threat model and executable acceptance tests.

## P1 — Model Router v2
**Owner:** Grok  
**Review:** ChatGPT + Gemini; alignment: Claude

Deliver the complete Router v2.1 document and Kotlin-oriented contracts. It must include:
- multi-label task classification, sensitivity and context requirements;
- provider registry, capabilities, context limits, data retention/location and rate limits;
- privacy as a hard eligibility gate before scoring (a privacy weight such as 0.35 must never override policy);
- task-specific quality, health/reliability, latency and cost evaluation;
- classified retry/fallback behavior, circuit breaker and request tracking;
- SSRF-safe user-defined endpoints and credential isolation;
- Persian benchmark methodology and Android resource budgets;
- local model feasibility supported by reproducible RAM/storage/latency/battery/thermal evidence.

**Acceptance:** evidence-backed, reproducible and aligned with Claude's contracts. No unsupported local-model feasibility claims.

## P2 — Existing PR and branch reconciliation
**Owner:** ChatGPT  
**Review:** Claude + Gemini

- PR #20: finalize archive-based evidence policy and master-plan integration; obtain independent review.
- PR #19: inspect plugin catalog/manager; specify gaps before runtime work.
- PR #18: review identity criteria, temporary generic VRM distinction, asset provenance and reference-image privacy.
- PR #4 and #5: inspect old base ancestry and complete diffs; reconcile in isolated branches and rerun CI before considering merge.
- Inventory remaining feature/fix/integration branches; branch presence does not prove code status.

**Acceptance:** explicit integration order, no unresolved merge conflicts, fresh CI and independent review.

## P3 — Implementation sequence after P0/P1
**Owner:** GitHub Copilot  
**Technical review:** Claude  
**Security review:** Gemini  
**Integration:** ChatGPT

1. Stabilize current main behavior and establish reproducible build/test baseline.
2. Incrementally extract ViewModel/UDF boundaries without behavior regression.
3. Add `IMemoryRepository` adapter over existing encrypted storage.
4. Implement permission/consent/audit enforcement before expanding tool access.
5. Implement provider contract and privacy-gated router without breaking current chat API.
6. Upgrade catalog into a real plugin runtime with persistent state and lifecycle.
7. Evaluate semantic/vector retrieval only after benchmark and migration design.
8. Integrate custom Yara avatar and viseme pipeline after model/license/device validation.
9. Add local model adapters only after Android resource benchmarks.

Every implementation item must be a separate scoped PR with tests, risk and rollback notes.

## Branch and review policy
- Branch names: `feature/<scope>`, `fix/<scope>`, `docs/<scope>`, `governance/<scope>`.
- No direct commits to `main`; no force-push.
- At least one independent technical review and passing required CI before merge; security-sensitive work also requires security review.
- Re-run CI after rebase or material changes.
- Do not merge solely because GitHub reports `mergeable=true`.
- Never commit API keys, OAuth tokens, signing keys, private reference images without authorization, or `local.properties`.

## Required task report format
Owner; issue/PR; branch; base SHA; exact changed paths; status (Proposed/Code present/Unit-tested/Integration-tested/Device-verified/Release-ready); commands and test results; artifact/checksum; device/OS evidence when relevant; known limitations; security/privacy risks; migration/rollback; next action.
