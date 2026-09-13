# Host transaction runtime for confirmed writes

Date: 2026-09-13. This extension lets a host keep confirmation intents, business writes and receipts in its own transaction system while the bridge continues to discover and invoke one annotated Spring MVC endpoint. It avoids implementing a separate set of protocol callbacks for each business action. It does not introduce any downstream application dependency into the library.

## Selecting the runtime

The default remains `@McpEndpoint(effect = Effect.WRITE)` with `confirmationRuntime = false`: the existing bridge operation repository and file workflow continue to own preparation, confirmation, revision and receipts. Existing file upload/export tools and authentication rules are unchanged.

To use host transactions, declare an explicit WRITE endpoint and supply exactly one `BridgeConfirmedWriteRuntime` Spring bean whose `capabilityName()` matches the endpoint's `name`:

```java
@PostMapping("/records")
@PreAuthorize("hasRole('EDITOR')")
@McpEndpoint(
    name = "records_create",
    description = "Create a record",
    effect = Effect.WRITE,
    confirmationRuntime = true,
    scopes = "records:write")
public RecordReceipt create(@Valid @RequestBody RecordInput input) {
  return recordService.create(input);
}
```

The annotation opts only this MCP capability into the host workflow; the HTTP route, request body, response and method permissions retain their existing meaning. A missing matching runtime, a duplicate runtime name, or READ combined with `confirmationRuntime = true` is a startup error. A supplied runtime cannot silently replace a capability that has not opted in. Runtime selection is fixed at registration; a client cannot choose a bean, method, route or callback during submission.

The SPI is `com.cogistra.mcpbridge.registry.BridgeConfirmedWriteRuntime`, in the starter module. A generic transactional implementation may serve multiple applications or be instantiated for multiple named capabilities. It should accept host repositories, policies and domain callbacks through its own abstractions; it must not require a specific application's account tables, DTOs or workspace paths.

## Published lifecycle

For the example above, the bridge automatically publishes these five tools and does not publish an immediately executable `records_create` tool:

| Tool | Input | Responsibility |
| --- | --- | --- |
| `records_create_prepare` | `requestId`, `arguments` | Validate and resolve a proposed write; save and return the complete server review without executing the Controller. |
| `records_create_submit` | `operationId`, `revision`, `payloadHash`, `review` | Recheck the owned confirmation and current authorization, then execute its stored canonical input and save the receipt atomically. No replacement business input is accepted. |
| `records_create_revise` | `operationId`, `revision`, `arguments` | Withdraw the old confirmation before validating replacement business input; produce a new version requiring a fresh review and confirmation. |
| `records_create_status` | Exactly one of `operationId` or `requestId` | Recover this capability's owned intent or receipt, including after a lost response. |
| `records_create_cancel` | `operationId`, `revision` | Cancel an unexecuted intent. This is not deletion or reversal of a business record. |

`bridge_operation_status`, `bridge_operation_revise` and `bridge_operation_cancel` continue to address the default bridge operation repository. They cannot look up or modify a host-owned intent. Clients must use the matching capability's five lifecycle tools for host runtime operations.

The bridge defines input envelopes, bounds request/result sizes, carries verified identities, applies configured capability scopes and permissions, and records the common invocation audit. The host defines the review, receipt, state and error payloads; it should document those schemas for clients. The host must return its durable operation identifier in the review so response loss can be recovered without inventing a second operation.

## Host duties that the SPI does not replace

1. **Trusted owner and current permission.** Derive actor, issuer/client and account identifiers only from `BridgePrincipal` or independently verified host security context. Bind intents and request idempotency to that owner. Reject cross-owner reads, edits, cancels and submissions without revealing the stored review. Revalidate current account, grants, token/scope and applicable business permissions inside the host transaction/lock boundary, including when returning a prior successful receipt. Initial bridge authorization is not a substitute for that recheck.
2. **Stable server review.** Resolve names and defaults, validate relationships and all business fields, and persist the canonical input actually reviewed. Return the full meaningful input, including derived/default values. Bind the digest to the canonical payload and revision. A matching digest is an integrity check, not proof that a human reviewed anything.
3. **Withdrawal before replacement validation.** For an owned matching revision, commit or otherwise make its invalidation authoritative before validating a replacement draft. If a draft fails validation, the old revision must remain unusable. Serialize this with submission so either a completed submission wins first, or the invalidation prevents it. The bridge deliberately checks only the revision envelope and that replacement `arguments` is an object before calling `revise`; the host must validate the business schema itself afterward. Reuse the same business rules as preparation. Malformed protocol envelopes and unauthenticated/unauthorized calls remain rejected at the bridge boundary.
4. **Atomic execution and receipt.** Under the appropriate authorization, intent and domain locks, verify ownership, unexpired state, version, digest and exact review. Derive invocation arguments solely from the saved canonical snapshot. Invoke the provided `ConfirmedInvocation` and commit business changes, intent state and receipt in one host transaction. Roll all of them back on execution failure. Handle domain results that represent failure without throwing; never persist them as success. A successful receipt must survive retries and cleanup. External side effects require a host-defined durable coordination/reconciliation strategy rather than claiming local transaction atomicity covers them.
5. **Safe idempotency and recovery.** The same owner's `requestId` must not create duplicate intents or silently switch to different business content. Concurrent submissions must create at most one business result. A lost response must return the original receipt on status/retry; an unknown outcome must remain distinguishable from a confirmed failure. Cancellation must not erase a successful result. Expiry and cleanup must preserve audit and receipt retention required by the host.
6. **Human confirmation remains a client obligation.** The client must display the complete server review, end that response and wait for a new explicit employee confirmation before submission. Any material edit requires a new review and confirmation. OAuth consent, a previous confirmation, a model-generated `confirmed=true`, or the client repeating the digest does not prove employee consent. The SPI cannot claim to authenticate an in-chat click or message unless a separate trusted client confirmation channel actually exists.

The `ConfirmedInvocation` callback is fixed to the registered endpoint's managed Spring bean. It authorizes the saved canonical arguments and binds/validates them again before invoking the Spring proxy, retaining method security and applicable advice. It does not create a replacement host transaction or discard an authentication context installed by the host runtime. The host should invoke it synchronously within the transaction that owns the intent and receipt, restore any context it installs in a `finally` block, and never expose the callback itself to model input.

## Dedicated MCP input without changing HTTP

`BridgeContractAdapter.inputSchema(Method)` may provide an explicit object schema for a supported endpoint. At most one adapter may provide the schema for a method. Make it closed (`additionalProperties: false`) and bounded; do not expose actor identity, grants, credentials or an unrestricted HTTP DTO as model input.

`mapInput` defines semantic mapping for the normal binding/permission path. In host runtime preparation, the SPI receives the original MCP `arguments`, not the output of `mapInput`: the host must validate, resolve and persist its own canonical input, consistent with the adapter and shared domain rules. Submission uses that canonical snapshot without rerunning name resolution or changing defaults. `mapOutput` remains available for the Controller's result; the host can store that projected invocation result as its receipt. The runtime's own review/status response is its separate contract and is not automatically mapped by the Controller adapter.

Mapping, conversion or validation failures while binding method-permission arguments return a sanitized `INVALID_ARGUMENT`, even when the public schema accepted the supplied value. Clients should correct the input instead of treating it as missing business permission. Adapter exception messages are not exposed. An actual method-security rejection still returns `FORBIDDEN`.

## Verification and limits

The new tests use a synthetic Controller, synthetic JWT decoder/accounts, a test-only memory intent repository and temporary bridge storage. They issue actual `/mcp` JSON-RPC requests and HTTP requests, checking observable business rows rather than annotation reflection alone:

- Five tools are registered; preparation creates no business row; the submitted title and actor match the saved review; retries do not create another row; the original HTTP body still works.
- A second employee cannot read, revise, cancel or submit the first employee's intent; cancelling an unexecuted intent prevents submission.
- An invalid replacement draft reaches host invalidation first, the old review cannot execute, and a valid replacement requires its new version, digest and exact review.
- Removed scope/role rejects submission, and method permission is still enforced on the managed Controller proxy even when a host policy removes authority after the initial check.
- Unsafe missing-runtime and READ/runtime declarations fail actual application startup.

Test sources: `HostConfirmedWriteRuntimeIntegrationTest` and `HostConfirmedWriteRuntimeRegistrationTest` in the starter module. These fixtures do not verify durable database transactions, production concurrency, crashes, real OAuth clients, or real human confirmation. A production host must validate those separately against its own approved environment. No database, deployment or publication is part of adding this SPI.

Suggested isolated command from the library root:

```powershell
mvn.cmd -pl mcp-bridge-spring-boot-starter -am "-Dtest=HostConfirmedWriteRuntimeIntegrationTest,HostConfirmedWriteRuntimeRegistrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

2026-09-13 verification: JDK 17.0.19, `mvn.cmd -pl mcp-bridge-spring-boot-starter -am "-Dmaven.jar.forceCreation=true" install` passed. The starter's 28 tests passed, including all six new host-runtime cases. Its core/files dependencies contributed 17 passes and one Windows symlink skip. Formatting checks and local installation passed; no business database, real employee client, remote publication or deployment was involved. The initial pending-execution note is superseded by this result. See `docs/verification.md` for the scope and retained log location.

2026-09-13 follow-up: review found that a schema-valid value rejected during permission-argument mapping or binding was incorrectly reported as `FORBIDDEN`. `BridgePolicyGate.authorize` now separates binding failures from method-security denials, using fixed safe messages for each. A new `HostConfirmedWriteRuntimeIntegrationTest` scenario prepares a whitespace title that becomes invalid after mapping, checks `INVALID_ARGUMENT` with no business row, corrects the same request and submits it, then verifies that a real role denial remains `FORBIDDEN` without another row. All 24 affected host-runtime, method-guard, servlet and native scenarios passed after this change, including the new one. Formatting checks and final local artifact installation passed; log: `target-host-runtime-error-verify.log`. Across both runs, 46 distinct cases passed and one Windows symlink case was skipped. No database or deployment was involved.
