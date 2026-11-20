# YARA Task Assignments

**Last updated:** 2026-11-20  
**Coordination branch:** `governance/team-coordination-2026-11-20`

## Team responsibilities

| Member | Primary responsibility | Expected deliverables | Boundaries |
|---|---|---|---|
| ChatGPT | Project management, system architecture integration, prioritization and quality gates | Integrated roadmap, architecture decisions, acceptance review | Does not claim code changes without repository evidence |
| Claude | Detailed architecture for Semantic Memory, Memory Orchestrator, Agent Framework and tool security | Architecture contracts, threat scenarios, test specifications, code review findings | Design/review first; implementation only when explicitly assigned |
| Grok | Model research, provider APIs, Model Router and local-model benchmarking | `YARA-MODEL-ROUTER-V2.md`, reproducible model/provider benchmark | No direct main changes; provide evidence and wait for scoped approval |
| Gemini | Independent architecture, security and release-readiness audit | `YARA-INDEPENDENT-AUDIT-*.md` with paths, evidence, severity and acceptance criteria | Independent reviewer; no implied GitHub write access |
| GitHub Copilot | Implementation and test work for approved, scoped issues | Feature branches, tests, PRs, build evidence | No direct commits to main; follow approved contracts |

The repository owner (Iman) retains final authority over sensitive actions, security policy, release approval and material scope changes.

## Immediate work queue

### P0 — Establish the actual source baseline
**Coordinator:** ChatGPT  
**Evidence owner:** Grok  
**Review:** Gemini

Required:
- identify the exact commit behind reported local 0.4.3;
- compare it with GitHub main;
- provide clean/dirty working-tree status;
- provide diff summary and test/build evidence;
- do not force-push or merge an unverified tree.

**Acceptance:** a reproducible commit/ref and a reviewed comparison report.

### P1 — Architecture contracts
**Owner:** Claude  
**Review:** ChatGPT + Gemini

Deliver:
- Memory Repository and consolidation contracts;
- Agent/Tool execution contracts;
- privacy-aware provider routing constraints;
- durable consent request lifecycle;
- audit event schema and threat model.

**Acceptance:** contracts are implementation-ready, internally consistent and include failure modes and tests.

### P1 — Model Router v2 research
**Owner:** Grok  
**Review:** ChatGPT + Gemini

Deliver `YARA-MODEL-ROUTER-V2.md` covering:
- task classification and confidence;
- provider registry and user-defined endpoints;
- privacy-first routing;
- fallback chain and retry classification;
- circuit breaker and health checks;
- latency/cost/quality measurement;
- Persian-language and Android local-model benchmark;
- resource budgets (RAM, storage, battery, thermal limits).

**Acceptance:** evidence-backed comparison, reproducible methodology, limitations and a staged recommendation. No unsupported claims of on-device feasibility.

### P2 — Implementation after contracts and baseline approval
**Owner:** GitHub Copilot  
**Review:** Claude + Gemini; integration by ChatGPT

Candidate sequence:
1. ViewModel/UDF extraction without behavior regression.
2. `IMemoryRepository` adapter over existing storage.
3. permission/consent/audit foundation.
4. provider abstraction and router contract.
5. plugin runtime lifecycle and persisted state.
6. semantic/vector retrieval only after benchmark and migration design.

Every task requires a separate feature branch and pull request.

## Branch and review policy

- Branch naming: `feature/<scope>`, `fix/<scope>`, `docs/<scope>`, or `governance/<scope>`.
- No direct commits to `main`.
- No force-push to shared branches.
- PR description must include scope, risk, tests, screenshots/logs where relevant, migration/rollback notes.
- At least one technical review and passing required CI before merge; security-sensitive work also requires security review.
- Secrets, API keys, signing keys and personal data must never be committed.

## Reporting format

Each task report must state: owner, branch, base SHA, changed files, status, tests, build artifact/checksum if applicable, blockers, risks and next action.
