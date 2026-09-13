# Changelog

## 0.1.0-SNAPSHOT — 2026-09-13 extension

- Added `BridgeGuidanceProvider` and bounded immutable `BridgeGuidance` assets; optional `bridge_get_guidance` provides permission-filtered catalogs, full Skill/reference text and deterministic private ZIP exports from one source. Initialization retains mandatory write guidance and adds only a short pointer. Downloading does not install a client Skill or confirm a business write.
- Added opt-in `@McpEndpoint(confirmationRuntime=true)` and `BridgeConfirmedWriteRuntime` for host-owned durable intents, full business review and business/receipt transaction boundaries. The common registry still creates the tools and invokes only the original managed endpoint.
- Added prepare/submit/revise/status/cancel tools for opted-in capabilities, with status recovery by operation or request ID. Replacement draft validation follows host withdrawal of the prior revision.
- Added `BridgeContractAdapter.inputSchema(Method)` for explicit narrow MCP input schemas while preserving the existing HTTP DTO contract. Missing runtimes and conflicting opt-in declarations fail registration.
- Separated permission-argument binding errors (`INVALID_ARGUMENT`) from actual method-security denial (`FORBIDDEN`) so clients can correct malformed input without unnecessary reauthorization.
- This is a local source extension, not a remote artifact release; see verification records for checks and pending real-client acceptance.

## 0.1.0-SNAPSHOT — 2026-09-12

Initial implementation, not yet published:

- Independent Spring Boot component with explicit controller tool registration and native Spring AI MCP annotations.
- Shared identity, business method security, policy restrictions, per-operation write preparation/confirmation and durable receipts.
- Reuse of active Spring pre-authorization, Secured and JSR-250 guards for fresh receipt permissions, with startup rejection of unsupported filtering/post-authorization and unknown policy keys.
- Authenticated uploads, file references, binary/Servlet exports and ordinary URL export results.
- Optional OAuth authorization server connected to a host account SPI.
- Synthetic standalone sample, isolated verification, integration and release documentation.
- User-confirmed Maven organization `com.cogistra`, Java package `com.cogistra.mcpbridge`, and MIT license with Cogistra copyright.

This entry describes the implemented local scope. Verification records distinguish completed checks from pending client and deployment acceptance.
