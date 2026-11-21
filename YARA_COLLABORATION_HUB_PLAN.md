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

## 6. Repository visibility and private-agent access

The repository is currently **public** (verified through GitHub repository metadata on 2026-11-21). Public visibility is not the intended long-term security posture for a project containing private architecture, internal agent reports or future operational data.

### Target state: private repository behind YARA Gateway

The target architecture is a **private GitHub repository** accessed through a central YARA GitHub App installation and YARA Gateway. Making the repository private does not require every model provider to have a GitHub account or direct collaborator permission.

The access path is:
1. Install a dedicated YARA GitHub App on this repository with only the required repository permissions (Contents: read/write; Pull requests: read/write; Metadata: read; Checks/Actions: read as required).
2. The Gateway obtains short-lived GitHub App installation tokens at runtime. Do not use or distribute the owner's personal access token.
3. Each provider/runtime authenticates separately to YARA Gateway using its own revocable agent identity.
4. The Gateway maps each agent identity to scoped capabilities and branch namespaces, and records all operations with agent ID, task ID and commit SHA.
5. Providers with remote MCP support use the YARA MCP endpoint; providers without compatible MCP support use a provider-specific runtime/API adapter. A2A is used for agent task exchange where supported.
6. All write operations target feature branches and pull requests. The Gateway rejects direct writes/merges to `main`. The owner alone approves promotion to `main`.

### Required safeguards

- No shared PAT or shared provider credential across agents.
- No provider API keys, GitHub tokens, signing keys or private user data in Git, issues, PR comments or shared project memory.
- Short-lived credentials, least privilege, per-agent revocation and audit receipts.
- Private repository contents are exposed only to authenticated agents with explicit project membership and data-classification clearance.
- A provider is not considered connected until an end-to-end read test, feature-branch write test, PR creation test and revocation test pass.

### Current transition status

- [x] Confirm current repository visibility is public.
- [x] Confirm the connected GitHub integration can read and write the current repository.
- [ ] Create/install a dedicated YARA GitHub App with the minimal permissions above.
- [~] Implement the initial authenticated YARA Gateway code on the feature branch; deployment and live GitHub App verification remain pending.
- [ ] Configure provider-specific runtime adapters and obtain credentials through owner-authorized OAuth/API flows.
- [ ] Run end-to-end read/write/revocation tests for each provider.
- [ ] Change repository visibility to private after the Gateway's GitHub App access is installed and verified, to avoid locking out the current integrations during transition.

The repository visibility change and provider account authorizations are owner-controlled operations. No visibility change or external provider authorization has been performed by this plan.

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
- [x] Validate the Android workflow on PR head `c483d3815905edd53c1f77eed30442cd33dccf22` (run #111): 18 unit tests passed, debug APK built and uploaded.
- [ ] Configure and test Main protection after owner review.

### Stage 3 — Collaboration service foundation
- [~] Implement the initial authenticated HTTP API and agent registry with per-agent credentials; provider-specific adapters remain pending.
- [ ] Implement durable mission/task/message persistence.
- [~] Implement initial branch-scope policy checks and append-only audit trigger; mission approvals and full policy engine remain pending.
- [~] Implement GitHub App JWT/token exchange and repository-allowlisted branch/file/draft-PR operations; live App installation and private-repository verification remain pending.
- [x] Add and run six Gateway unit tests for token generation, comparisons, agent identity validation, path traversal, branch isolation and protected paths (Gateway run #18 succeeded on `c483d3815905edd53c1f77eed30442cd33dccf22`). Integration, concurrency, replay and recovery tests remain pending.

### Stage 4 — Agent integrations
- [ ] Integrate one reviewer agent first, then one implementation agent.
- [ ] Add provider adapters one at a time using independent credentials; expose one authenticated MCP endpoint plus API/runtime adapters for providers without MCP support.
- [ ] Add A2A interoperability where supported and MCP tool access through the Tool Gateway.
- [ ] Verify each agent's read/write scope and revocation independently.
- [ ] Expand to remaining agents only after passing the same acceptance suite.

### Stage 5 — Operational readiness
- [~] Prepare Railway Infrastructure as Code for a Gateway service and PostgreSQL database; Railway account connection and plan/apply remain pending.
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

The owner has authorized the implementation work and the initial staging host has been selected as Railway. The Railway IaC declaration is prepared, but no paid resource has been provisioned. No recurring charge will be incurred until the account's plan and expected cost are visible and within an explicit budget.

The following actions still require the owner's authenticated account session because the connected tools cannot perform them:
- create the dedicated GitHub App and generate its private key;
- connect the Railway account and approve any billable infrastructure plan;
- authorize provider accounts and place their individual tokens in the relevant secret stores;
- change repository visibility or enforce branch-protection settings;
- approve any release/signing action;
- approve every merge into `main`.

This authorization allows implementation, testing and preparation on feature branches. It does not grant agents blanket access or bypass the owner-only `main` promotion rule.

## 11. Current implementation evidence (2026-11-21)

The following code has been committed to the execution branch, not to `main`:
- `gateway/src/server.mjs`: Fastify API, PostgreSQL agent registry, one-time owner bootstrap provisioning, token rotation/revocation, GitHub App JWT and short-lived installation-token exchange, repository allowlist, branch creation, file read/write, draft PR creation and audit events.
- `gateway/src/security.mjs` and `gateway/test/security.test.mjs`: credential hashing/generation, constant-time comparison, input/path validation and agent branch isolation tests.
- `gateway/Dockerfile`, `gateway/package.json`, `gateway/.env.example`, `gateway/README.md`: container and local deployment scaffolding.\n- `gateway/scripts/smoke-mcp.sh`: post-deployment SDK-client check for HTTPS, modern/legacy protocol negotiation, tool discovery, identity and unauthenticated denial; shell syntax passed in Gateway job #162.
- `.github/workflows/android.yml`: Gateway syntax and unit-test job added to the existing PR workflow.
- `.railway/railway.ts`: Railway Infrastructure as Code declaration for a PostgreSQL database and Gateway service; it has not been applied to an account.
- `gateway/GITHUB_APP_SETUP.md`: exact minimal GitHub App permission and secret-injection checklist.
- `docs/collaboration/PROVIDER_CONNECTIONS.md`: provider-specific MCP connection steps and actual plan/surface limitations.

**Verified on code commits (2026-11-21):** Unified workflow #173's Gateway job passed dependency installation, JavaScript syntax checks, all 6 security unit tests, `bash -n` validation of the MCP smoke script and Docker image build on code commit `8b9ac13dbeb0adf20bc7708d35b5244628ca3a1e`. Android workflow #143 passed all 18 Android unit tests, built and uploaded the debug APK on code commit `fa76a353e95f1fe13416821a4aff05e60322f569`; APK SHA-256 `1c3679d568ec7e5eef3db5057ae8b8ce30ee5dd8b9d9349407377c381904aea3`. Android workflow #173 is validating the latest branch changes.

**Not yet verified:** Railway IaC plan/apply, PostgreSQL integration, real GitHub App authentication, Codespaces execution, production deployment, provider connections, repository-private transition and enforced branch protection. The generated APK is a CI debug build, not a signed production release. The Gateway and Android checks above are CI evidence for their respective code commits; subsequent changes to the branch have been documentation-only. No live service or provider integration is implied by those tests.

## 12. Status reporting format

Every agent contribution must include:
- agent ID and runtime identity;
- task and parent mission IDs;
- exact branch, commit SHA and changed paths;
- evidence and tests actually run, with results;
- known limitations and unresolved risks;
- any external data or provider used;
- whether the output is a proposal, implemented change, tested change or independently verified result.

Claims without reproducible evidence remain unverified.
