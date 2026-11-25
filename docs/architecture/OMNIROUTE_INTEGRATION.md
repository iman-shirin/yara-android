# OmniRoute integration evaluation for YARA

**Status:** Architecture evaluation / implementation plan only  
**Date:** 2026-11-24  
**Repository:** `iman-shirin/yara-android`  
**Decision:** Adopt OmniRoute as an optional, replaceable model-provider gateway after isolated validation. Do not replace YARA's model-independent core, memory, collaboration Gateway, or Cloudflare Worker.

## 1. Verified upstream facts

Upstream project: https://github.com/cheaper-inference/OmniRoute  
License: MIT (verify the exact release tag before vendoring or redistributing).  
Upstream quick start currently documents:

- Node.js runtime: `>=22.22.2 <23 || >=24.0.0 <27`.
- Local dashboard: `http://localhost:20128`.
- OpenAI-compatible API: `http://localhost:20128/v1`.
- Connect providers in Dashboard → Providers, then create a key in Dashboard → Endpoints.
- Client configuration uses the local API base URL, the generated endpoint key, and a model or `auto`.
- Provider catalog size and free-tier counts are catalog metadata, not a promise that every provider/model is free, available, or usable without signup/payment details.
- The project documents Android/Termux, Docker, remote mode, MCP and A2A support.

Primary references:
- https://github.com/cheaper-inference/OmniRoute
- https://github.com/cheaper-inference/OmniRoute/wiki/setup-guide
- https://github.com/cheaper-inference/OmniRoute/wiki/Provider-Reference
- https://github.com/cheaper-inference/OmniRoute/wiki

Re-check these upstream docs and the selected release tag before every installation. Provider counts, dependencies, supported runtimes and command syntax can change.

## 2. YARA architecture decision

OmniRoute is a provider gateway, not YARA's application backend or mission-control system.

Target request path:

```text
YARA Android / Windows clients
          |
          v
YARA Model Router (provider-neutral contract, policy, task classification)
          |
          v
YARA Gateway / private service network
          |
          v
OmniRoute (optional upstream adapter)
          |
          +--> approved cloud provider connections
          +--> explicitly configured local model runtimes

YARA core memory, identity, permissions, audit, task coordination and approval policy
remain independent of OmniRoute.
```

The YARA Cloudflare Worker is not an OmniRoute host. OmniRoute uses a persistent Node.js application stack and local persistent state; it must run as a separate service on a supported host. The existing Worker may call a separately hosted OmniRoute service only through a private/authenticated network path after a security review. Do not attempt to bundle OmniRoute into the Worker or D1 runtime.

## 3. Host selection and current constraints

### Windows laptop

The previously recorded laptop has an Intel Core i3-M 380, 4 GB RAM and a 500 GB HDD. That is below the upstream Docker recommendation for a coding-agent workload (8 GB Node heap and a container of at least 10 GB in the current upstream README). Do not install a full always-on coding-agent deployment on this laptop without first upgrading RAM and validating CPU/disk performance.

A lightweight dashboard or one-request smoke test may be possible, but should not be treated as a reliable production host.

### Android phone / Termux

The upstream project documents Termux support. This is the most accessible phone-only experiment, but should be treated as a temporary local test, not the production YARA gateway. Mobile OS process suspension, thermal limits, battery optimization, storage permissions and network changes can interrupt service.

Do not expose port 20128 to the public internet. Keep it bound to localhost unless a separately reviewed private tunnel and authentication arrangement is in place. Do not place provider credentials in shell history, screenshots, source control, YARA Android preferences or chat messages.

### Preferred long-term host

Use a separately managed host with supported Node.js and persistent storage, adequate RAM, process supervision and encrypted backups. A free tier may be evaluated, but no hosting provider, paid plan, deployment or billing change is approved by this document. Do not deploy until zero-charge controls and provider terms are verified.

## 4. Provider and cost policy

1. Start with no provider credentials and verify the dashboard/API health locally.
2. Connect only providers whose current terms explicitly permit the intended client and workload.
3. Classify each connection as `free-tier`, `subscription/OAuth`, `prepaid/paid API`, or `local`; never infer free status from a catalog badge alone.
4. Exclude providers marked by upstream as terms-of-service restricted/avoid from automatic routing.
5. Disable paid fallback routes by default. A paid provider requires explicit owner approval, budget cap and usage alert before activation.
6. Use provider-specific credentials and least-privilege scopes. Rotate/revoke credentials on suspected exposure.
7. Do not use browser-cookie or account-session extraction paths unless the provider's terms and upstream documentation explicitly authorize the exact method.
8. Keep usage/cost telemetry enabled where it does not expose prompts or secrets; redact credentials and sensitive user content from logs.

## 5. YARA integration phases and acceptance criteria

### Phase A — upstream and package review (phone-only research completed)

- Confirm repository, license, release tag, supported Node versions and install paths.
- Review security policy, dependency lifecycle scripts, provider terms, remote-mode design and data retention.
- Pin an exact version/tag and record its checksum before installation.
- No production credentials or deployment.

### Phase B — isolated Termux proof of concept

- Install Termux from a trusted source and update packages.
- Install the supported Node.js runtime required by the pinned OmniRoute release.
- Install the pinned OmniRoute version; inspect npm lifecycle-script warnings before allowing scripts.
- Start locally and verify dashboard and `/v1/models` with a locally generated endpoint key.
- Initially connect no paid provider. Then test one explicitly free, terms-compliant provider only if its eligibility and signup conditions are confirmed.
- Verify restart behavior and document Android background-process limitations.
- Remove the test instance and revoke test credentials after validation if the phone cannot keep it reliable.

### Phase C — isolated Windows/server deployment

- Select a host only after measuring RAM, CPU, disk, persistent storage and network stability.
- Run OmniRoute as a separate service with pinned version, non-root account, persistent data volume, loopback/private binding, automatic restart and encrypted backups.
- Do not run an exposed unauthenticated listener.
- Test health, API authentication, model listing, streaming, timeout, fallback, rate limits, restart recovery and log redaction.

### Phase D — YARA adapter (feature branch only)

Implement a provider-neutral adapter in YARA, not direct OmniRoute-specific logic throughout the app:

- Configurable base URL and model alias.
- Secret storage for the OmniRoute endpoint key; never store it in plaintext preferences or source.
- OpenAI-compatible request/stream parsing with explicit compatibility tests.
- Provider/model capability metadata (tools, vision, JSON mode, context limits, streaming).
- Timeouts, cancellation, bounded retries and circuit breaker.
- Error normalization and safe fallback to the existing YARA provider path.
- No silent paid fallback; cost policy must be checked before dispatch.
- Redacted diagnostics and auditable provider/model selection.
- Feature flag default OFF; direct existing providers continue working if OmniRoute is offline.

### Phase E — multi-agent and MCP

OmniRoute's MCP/A2A features are separate from YARA's collaboration MCP Gateway. Do not merge their trust domains. If later connected, expose only a reviewed allowlist of tools, use separate credentials, and prevent OmniRoute from granting itself YARA repository or mission permissions.

## 6. Security review checklist

- [ ] Pin and verify upstream release/tag and license.
- [ ] Review upstream security policy and known advisories.
- [ ] Review npm install scripts and dependency tree.
- [ ] Bind service to localhost/private network only.
- [ ] Require endpoint authentication and rotate keys.
- [ ] Keep secrets out of Git, app resources, logs and prompts.
- [ ] Disable paid providers/fallbacks until owner-approved.
- [ ] Validate provider terms and free-tier eligibility.
- [ ] Configure data retention, backup and deletion policy.
- [ ] Test prompt-injection and tool-output boundaries.
- [ ] Ensure OmniRoute cannot bypass YARA authorization, approvals or audit.
- [ ] Verify graceful behavior when OmniRoute is unavailable.

## 7. Current execution status

| Work item | Status |
|---|---|
| Upstream project and MIT license identified | Research verified; re-check exact pinned tag |
| Current runtime/API and quick start reviewed | Research completed |
| Termux is documented upstream | Research verified |
| YARA architecture fit assessed | Decision recorded |
| Existing YARA Worker remains separate | Decision recorded |
| Branch for this evaluation created | Done |
| OmniRoute installed on a device | Not done |
| Provider credentials added | Not done |
| OmniRoute endpoint reachable | Not done |
| YARA adapter implemented | Not done |
| Production deployment or billing change | Not done; not authorized |

## 8. Next owner/device-dependent actions

The current Desktop Commander device is offline, so Windows installation and local hardware checks cannot be performed remotely. Continue with the phone-only Termux proof of concept when the user is ready to run commands. Do not represent research or documentation as an installation.

Before any production rollout, open a separate implementation PR with code, tests, pinned dependency evidence and a rollback plan. This evaluation document alone does not authorize merge, deployment, payment or provider credential entry.


## 9. APInex evaluation (2026-11-25)

### 9.1 What APInex is

Official service: https://apinex.bond/  
API root: `https://api.apinex.bond/v1`  
Developer reference: https://apinex.bond/developers  
Machine-readable reference: https://apinex.bond/llms-full.txt

APInex is a hosted, unified model/API gateway, not an open-source provider router equivalent to OmniRoute. Its documented interfaces include:

- OpenAI-compatible Chat Completions: `POST /v1/chat/completions`.
- OpenAI Responses: `POST /v1/responses`.
- Anthropic Messages: `POST /v1/messages`.
- Model discovery: `GET /v1/models`.
- Balance and subscription/quota endpoints: `GET /v1/balance`, `GET /v1/subscription`.

It uses one `sk-apx...` credential and a hosted USD wallet for paid models. Its documentation describes free-category models as a separate daily allowance metered by model-specific weights; free-category models do not charge the USD wallet while eligible allowance remains.

### 9.2 Catalog and free-tier caveats

The public Models & Pricing page currently displays 53 model entries and marks 23 entries as Free. The machine-readable `llms-full.txt` currently labels its own catalog as 46 models and enumerates 22 `free/*` entries. These published catalog counts do not agree. Treat the live authenticated `GET /v1/models` response and `GET /v1/subscription` response as the operational source of truth at test time.

The free labels are not a promise of unlimited or unrestricted access:

- Free requests use a shared daily token allowance and model-specific weights (the published reference shows weights from 2x to 5x for listed free models).
- The published free account rate limit is 15 requests/minute per account, shared across keys.
- The marketing/pricing pages disagree on the default daily allowance: one page says 3M tokens/day, another says 10M tokens/day. Do not hard-code either value. Read `token_limit`, `tokens_remaining`, `resets_at_utc`, and `allowed` from the authenticated subscription endpoint.
- Some guides explicitly warn that a model carrying a Free label may have `allowFree: false` or may not be available to a particular account. Check each model's current eligibility and test actual responses.
- The status page reports per-model operational/high-load/outage states; free models should not be treated as reliable always-on production capacity.

A free model entry is therefore best classified as `listed-free / eligibility-unverified` until an authenticated test confirms the account can call it without consuming USD wallet balance.

### 9.3 Pricing and payment boundaries

APInex is not an entirely free service. Its catalog includes paid pay-as-you-go models, and its pages advertise paid subscriptions that raise the free-category allowance. The developer reference states that $1 credits 1,000,000 balance tokens for wallet accounting, and paid models are billed at published input/output rates. Web research, search, and other tools may have separate per-call charges.

YARA policy for APInex:

1. Do not add funds, buy a subscription, redeem a paid voucher, or upgrade RPM.
2. Do not enable paid model IDs or paid tools in any automatic route.
3. Keep paid-wallet balance at zero unless the owner explicitly approves a budget.
4. Permit only model IDs verified as eligible for the account's free allowance.
5. Read and record quota before and after each test; assert that wallet balance/spend has not changed.
6. Set an explicit allowlist rather than accepting every model returned by the catalog.
7. On 401/402/403/429, quota exhaustion, or ambiguous billing response, fail closed; never retry through a paid model.

### 9.4 Compatibility with OmniRoute and YARA

OmniRoute's current User Guide documents custom OpenAI-compatible provider nodes with a base URL ending in `/v1`, an API type such as Chat Completions or Responses, a unique non-reserved routing prefix, and a separately stored connection credential.

A plausible integration path is:

- Register APInex as a custom OpenAI-compatible provider in OmniRoute.
- Base URL: `https://api.apinex.bond/v1`.
- API type for first test: Chat Completions.
- Use a unique prefix such as `apx` (confirm the final model-id syntax against the pinned OmniRoute release).
- Store the APInex key only in OmniRoute's credential store; never in YARA source, Android preferences, logs or prompts.
- Import only explicitly approved free model IDs after account-level eligibility has been checked.

APInex also documents its own Anthropic Messages and OpenAI Responses endpoints. Do not assume OmniRoute's custom-provider node translates all three protocols identically. Test Chat Completions first; test Messages/Responses separately against the pinned OmniRoute version before using Claude Code or Codex compatibility claims.

YARA may also integrate APInex directly through a provider-neutral adapter. The adapter must preserve YARA's cost policy and must not make APInex a dependency of YARA memory, identity, authorization, approvals or task state.

### 9.5 Privacy and provenance

APInex's own integration guide claims that it operates as a zero-retention stateless proxy and does not retain prompt/completion payloads. This is a vendor statement, not an independently verified audit or contractual guarantee. Public privacy/terms details and upstream model provenance have not been independently validated in this evaluation.

Until legal terms, data-processing terms, upstream provider identity, retention, training-use policy, subprocessors, region, deletion, and incident handling are reviewed, do not send sensitive personal data, credentials, private repository content, or YARA memory to APInex. Use synthetic/non-sensitive test prompts only.

Model labels in a third-party catalog (for example names containing GPT, Claude or Gemini) are not proof that the request is served by the official provider's model endpoint. Record provider/model provenance as unverified unless APInex supplies evidence sufficient for the intended use.

### 9.6 Current comparative decision

| Dimension | OmniRoute | APInex |
|---|---|---|
| Product type | Self-hosted open-source routing gateway | Hosted multi-model API gateway |
| Primary role | Provider aggregation, routing, fallback, policy boundary | Single API and account for APInex-hosted models/tools |
| Credentials | User connects each upstream provider | One APInex API key |
| Free access | Depends on each connected provider's terms/quota | Published daily weighted free-model allowance; eligibility and quota must be checked |
| Paid access | Depends on connected providers | Wallet pay-as-you-go and paid quota subscriptions |
| Hosting/control | Can be self-hosted | Hosted service; separate APInexGate reseller project is not the same as the hosted service |
| YARA fit | Optional replaceable routing layer | One optional upstream source behind the provider abstraction / OmniRoute |

Decision: retain OmniRoute as the optional, replaceable routing gateway and evaluate APInex as one optional upstream provider. Do not replace OmniRoute with APInex and do not make either mandatory to YARA. This is a design decision only; it does not authorize account creation, key generation, payment, deployment, merge or production traffic.

### 9.7 APInex validation checklist

- [ ] Review APInex Terms and Privacy Policy; obtain clarity on retention, training, subprocessors and upstream provenance.
- [ ] Create a test account only with owner approval; do not add payment details or wallet funds.
- [ ] Retrieve authenticated `/v1/models`, `/v1/subscription`, and `/v1/balance`; record exact account eligibility and limits.
- [ ] Select one low-risk model marked free and verify `allowed=true` before calling.
- [ ] Run a synthetic non-sensitive Chat Completions request; inspect usage, cost fields, quota decrement and wallet balance.
- [ ] Verify that a quota-exhausted request fails closed and does not route to paid models.
- [ ] Test streaming, tool calling, JSON mode, images and long context separately; do not infer these capabilities from marketing tables.
- [ ] Test APInex as an OmniRoute custom provider node on a pinned release.
- [ ] Verify the final routed model ID and inspect logs for credential/prompt leakage.
- [ ] Record latency, uptime, error rate, context behavior and rate-limit response over repeated tests.
- [ ] Keep the provider disabled by default until tests and policy review pass.

### 9.8 Evidence and unresolved points

Reviewed public sources:

- https://apinex.bond/models
- https://apinex.bond/pricing
- https://apinex.bond/developers
- https://apinex.bond/llms-full.txt
- https://apinex.bond/status
- https://apinex.bond/guides/unified-openai-compatible-gateway
- https://apinex.bond/developers/models/chat
- https://github.com/cheaper-inference/OmniRoute/wiki/User-Guide

Unresolved: authenticated API behavior, actual per-account free eligibility, daily quota discrepancy, exact model catalog count, data-processing terms, independent security audit, upstream model provenance, and end-to-end compatibility through OmniRoute. No API key was available or used; no API call was sent to APInex.

