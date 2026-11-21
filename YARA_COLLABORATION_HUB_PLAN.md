# YARA Collaboration Hub — Execution Plan

**Owner:** Iman (final authority)  
**Repository:** `iman-shirin/yara-android`  
**Verified baseline:** `main` @ `b77624b2626883c528f3178d520c42d7a29f4ce4`  
**Execution branch:** `feat/yara-collaboration-hub-foundation`  
**Status:** Owner-authorized; staged implementation in progress  
**Main-branch policy:** No agent or automation may merge to the main branch. Owner approval is mandatory for every promotion to `main`.

## 1. Objective

Establish one shared collaboration environment in which ChatGPT, Claude, Grok, Perplexity, Gemini, Copilot and future agents can work against the same YARA project state, exchange task results and evidence, and contribute code or documentation without creating competing project copies.

The system must separate:
- the Android product;
- the durable collaboration/control plane;
- model providers;
- autonomous agent identities and runtimes;
- tool access;
- shared project knowledge and audit history.

A model provider is not automatically an autonomous agent. A ChatGPT conversation is not assumed to be a persistent background process. Every always-on agent requires a separately deployed runtime and its own authenticated identity.

## 2. Source-of-truth boundaries

| Data | Authoritative location | Write policy |
|---|---|---|
| Android application source | GitHub repository | Feature branches and reviewed PRs only |
| Architecture decisions and project plans | Versioned Markdown in repository | Agent proposals allowed; owner approves material decisions |
| Agent identities, tasks, messages and approvals | Collaboration service database | Authenticated, scoped API writes |
| Large generated artifacts and test reports | Artifact storage, linked from tasks/PRs | Per-agent scoped upload |
| Secrets and provider credentials | Secret manager / deployment environment | Never stored in Git, prompts, issues or shared memory |
| CI results and build artifacts | GitHub Actions | Workflow-generated, tied to commit SHA |
| Audit trail | Append-oriented audit store | No agent may rewrite or delete history |

GitHub is the code and documentation source of truth. It is not, by itself, a durable multi-agent message bus or a secret manager.

## 3. Logical architecture

1. **Owner Control Surface** — mission queue, agent health, approvals, audit and review.
2. **YARA Orchestrator** — plans work, delegates bounded tasks, resolves conflicts and tracks deadlines/budgets.
3. **Agent Registry** — stable agent IDs, runtime identity, capabilities, protocol versions, health and policy scopes.
4. **Collaboration Transport** — durable messages, correlation IDs, retries, deduplication, ordering and dead-letter handling.
5. **Agent Runtime Adapters** — vendor-specific execution for Claude, Gemini, Grok, Perplexity, ChatGPT/API-backed agents and local models.
6. **Shared Knowledge Service** — project documents, task context, decisions, provenance and sensitivity labels.
7. **Policy & Approval Gateway** — authorizes each action at execution time; owner approval for sensitive actions.
8. **Tool Gateway** — MCP and internal tools with typed schemas, scoped credentials and execution receipts.
9. **Audit & Observability** — append-oriented events, redacted logs, trace IDs, health and metrics.
10. **GitHub/Codespaces/Actions adapter** — repository reads, feature-branch writes, PR creation, CI status and artifacts.

### Protocol responsibilities

- **A2A** is the agent-to-agent task and capability interaction layer where supported.
- **MCP** exposes tools and resources to agents; it does not replace agent identity, mission state or peer collaboration.
- **YARA internal event contracts** are the canonical durable message format.
- Provider APIs are inference transports, not the collaboration protocol.

Agents that do not natively support A2A or MCP can be wrapped by a YARA runtime adapter. No provider is assumed to support every protocol directly.

## 4. Access model

Every agent receives a unique identity and separately revocable credentials. Do not share the owner's personal access token or a single administrator credential among agents.

Default permissions:
- Read project source and approved project knowledge.
- Create/update its own task outputs, reports and evidence.
- Create commits only on scoped feature branches when granted repository-write capability.
- Open or update pull requests within its assigned scope.
- Read CI status and artifacts relevant to its tasks.
- Request additional permissions through the policy gateway.

Prohibited without explicit owner approval:
- Push, merge, force-push or delete on `main`.
- Change repository visibility, branch protection, secrets, billing or organization settings.
- Publish a release or signed production artifact.
- Delete project history, audit records or shared source data.
- Execute external actions with financial, legal, account, device-administration or irreversible impact.

Authorization is checked immediately before execution. Delegation never increases the sender's privileges. Missing, expired or ambiguous approval means deny.

## 5. Main-branch protection and review

Required target configuration:
- Protect the main branch against direct pushes and force pushes.
- Require a pull request for all changes.
- Require successful CI checks.
- Require at least one independent review and an explicit owner approval for every merge.
- Dismiss stale approvals when the PR changes.
- Prevent agents and automation from bypassing owner approval.
- Keep merge permissions limited to the owner.

These settings are **not yet verified or changed**. Configure and test them before granting broad write access to external agents.

## 6. Public-repository security condition

At execution start, GitHub reported this repository as **public**. Therefore all committed source, documentation, issues, PRs and branch contents may be visible to the public.

Until the owner explicitly changes repository visibility:
- commit only material intended for public disclosure;
- never commit API keys, OAuth tokens, passwords, signing keys, personal records or private agent transcripts;
- store credentials only in a secret manager or protected deployment secrets;
- review generated logs and artifacts for accidental disclosure.

Repository visibility is an owner decision and has not been changed by this plan.

## 7. Deployment topology

Use GitHub Codespaces as a reproducible development environment, not as the production always-on orchestrator. Codespaces can be stopped or expire and must not be treated as a durable service.

The collaboration control plane must run as a separately deployable service with:
- authenticated API and TLS;
- durable relational storage (PostgreSQL or equivalent);
- durable queue/outbox and dead-letter handling;
- secret injection from a managed secret store;
- backups, migrations and recovery tests;
- health checks, metrics and redacted structured logs.

Hosting vendor, data region, budget, identity provider and retention policy remain deployment decisions. Do not deploy a public endpoint or connect paid provider accounts until those decisions and secret scopes are approved.

## 8. Staged implementation

### Stage 0 — Baseline and governance
- [x] Verify the repository and current default branch.
- [x] Verify the connected GitHub identity and repository permissions.
- [x] Confirm the current `main` commit SHA.
- [x] Create an isolated execution branch.
- [x] Discover existing governance PR #20 and YARA-MACH RFC issue #21.
- [ ] Review and reconcile PR #20 governance documents with this plan.
- [ ] Obtain independent contract/security reviews requested in issue #21.

### Stage 1 — Collaboration contracts
- [ ] Finalize versioned Agent, Task, Message, Mission, Memory, Approval and Audit contracts.
- [ ] Define capability declarations separately from granted permissions.
- [ ] Define task lifecycle, cancellation, expiry, retries, idempotency and dead-letter recovery.
- [ ] Define data classification and provider eligibility rules.
- [ ] Define tool invocation schemas and execution receipts.
- [ ] Define testable threat scenarios and acceptance criteria.

### Stage 2 — Shared developer workspace
- [x] Add an initial Codespaces/devcontainer configuration for the Android toolchain (execution in a real Codespace remains unverified).
- [ ] Document local setup, environment variables and secret handling.
- [x] Add shared agent contribution instructions; PR/issue templates remain pending.
- [ ] Validate the existing Android workflow on the exact candidate commit.
- [ ] Configure and test Main protection after owner review.

### Stage 3 — Collaboration service foundation
- [ ] Implement authenticated service API and agent registry.
- [ ] Implement durable mission/task/message persistence.
- [ ] Implement policy checks, scoped approval requests and append-only audit events.
- [ ] Implement GitHub adapter for branch/PR operations; block Main writes in code and policy.
- [ ] Add integration, concurrency, replay, authorization and recovery tests.

### Stage 4 — Agent integrations
- [ ] Integrate one reviewer agent first, then one implementation agent.
- [ ] Add provider adapters one at a time using independent credentials.
- [ ] Add A2A interoperability where supported and MCP tool access through the Tool Gateway.
- [ ] Verify each agent's read/write scope and revocation independently.
- [ ] Expand to remaining agents only after passing the same acceptance suite.

### Stage 5 — Operational readiness
- [ ] Deploy to the owner-approved host and region.
- [ ] Verify backups, restore, monitoring, rate limits and cost ceilings.
- [ ] Run multi-agent concurrency and failure-injection tests.
- [ ] Verify owner approval is required for every Main promotion.
- [ ] Publish an evidence-based readiness report; do not claim integrations that have not passed tests.

## 9. Minimum acceptance tests

1. Two authenticated agents exchange a correlated request/response.
2. Three agents collaborate on one mission with bounded fan-out.
3. Duplicate message delivery does not duplicate side effects.
4. Revoked, expired or unauthorized agents cannot read or write restricted resources.
5. Delegation cannot elevate permissions.
6. Prompt injection in peer messages or tool output cannot change policy or grant tools.
7. A changed action parameter invalidates a previous approval.
8. Approval timeout or policy-service outage fails closed.
9. Provider/runtime disconnects recover without losing durable task state.
10. Main-branch writes and merges are rejected for all agent identities.
11. Every tool side effect has an auditable execution receipt.
12. Audit events correlate mission, task, message, tool call, approval and result.
13. Secret scanning and artifact scanning pass before PR promotion.
14. Android CI and relevant service tests pass on the exact PR head SHA.

## 10. Owner approval gates

Owner approval is required for:
- repository visibility changes;
- selecting hosting vendor, region and recurring spend;
- connecting provider accounts or granting external agents repository write;
- changing data retention, privacy or secret-handling policy;
- changing Main protection or merge permissions;
- merging any PR into `main`;
- releases, signing and production deployment.

This execution plan records the owner's authorization to begin the architecture work. It does not grant agents blanket access or authorize any of the gated actions above.

## 11. Status reporting format

Every agent contribution must include:
- agent ID and runtime identity;
- task and parent mission IDs;
- exact branch, commit SHA and changed paths;
- evidence and tests actually run, with results;
- known limitations and unresolved risks;
- any external data or provider used;
- whether the output is a proposal, implemented change, tested change or independently verified result.

Claims without reproducible evidence remain unverified.
