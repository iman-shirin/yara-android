# YARA Architecture Decisions

**Last updated:** 2026-11-20  
**Status:** Working decision register; decisions marked Proposed/Accepted are not a substitute for owner approval where required.

## ADR-001 — GitHub main is the verified shared baseline
**Status:** Accepted for coordination  
**Decision:** Use the current GitHub `main` as the only verified shared baseline until the reported local 0.4.3 snapshot is tied to a commit and reviewed.  
**Rationale:** Prevent divergent local work from silently replacing the shared project history.  
**Constraints:** No force-push; promotion through reviewed PR only.

## ADR-002 — Provider-independent model boundary
**Status:** Accepted direction; contract pending  
**Decision:** AI orchestration must depend on a provider-neutral interface, not vendor SDKs.  
**Requirements:** capabilities, availability, timeout, cancellation, streaming, structured output, typed errors and privacy metadata.  
**Open:** finalize exact Kotlin API and compatibility strategy with existing Groq/Gemini code.

## ADR-003 — Privacy-first model routing
**Status:** Accepted security principle  
**Decision:** Data sensitivity and user policy are hard constraints evaluated before cost, latency or quality. Sensitive data must not be sent to cloud providers merely because they score better.  
**Fallback:** If no permitted provider is available, stop or request explicit, scoped user consent.

## ADR-004 — Layered memory behind repository contracts
**Status:** Accepted direction; migration design pending  
**Decision:** Separate short-term context, episodic history, semantic facts and user documents. Access through repository interfaces.  
**Requirements:** provenance, confidence, timestamps, sensitivity, conflict handling, deletion propagation and schema migrations.  
**Open:** vector index selection, embedding model, retention policy and token-budget algorithm require benchmark and threat review.

## ADR-005 — Tool execution is mediated by a permission gateway
**Status:** Accepted security principle; implementation pending  
**Decision:** Every tool call is checked at execution time against tool scope, data sensitivity and risk tier. High-impact operations require explicit owner approval bound to the exact action and parameters.  
**Requirements:** persistent consent request IDs, expiry-deny semantics, cancellation safety, parameter binding and tamper-resistant audit design.

## ADR-006 — Agent execution is bounded
**Status:** Accepted direction; contract pending  
**Decision:** Agent loops have limits on iterations, elapsed time, tool calls and resource/cost budget. Repeated actions and unauthorized tool calls are blocked.  
**Open:** define retry policy, idempotency and recovery behavior.

## ADR-007 — Android presentation uses UDF/ViewModel boundaries
**Status:** Accepted direction; migration staged  
**Decision:** Move UI state and event handling from the activity into ViewModels/use cases incrementally.  
**Requirements:** immutable UI state, lifecycle-safe work, persistent consent state and avatar state derived from public assistant state—not internal agent implementation.

## ADR-008 — Plugin catalog is not equivalent to plugin runtime
**Status:** Accepted  
**Decision:** A plugin is considered implemented only when lifecycle, invocation, permissions, dependency checks, persistence, error handling and tests exist. A catalog entry or UI toggle alone is not proof of execution capability.

## ADR-009 — Release readiness requires device evidence
**Status:** Accepted  
**Decision:** A successful debug CI build does not establish release readiness. Release claims require signed artifact, checksum, clean install/upgrade test, permission review, device test matrix and rollback/recovery notes.

## Change control

Any material change to these decisions must be proposed in a PR with:
- problem statement and alternatives;
- security/privacy implications;
- migration and rollback plan;
- tests and measurable acceptance criteria;
- owner approval where sensitive or release-affecting.
