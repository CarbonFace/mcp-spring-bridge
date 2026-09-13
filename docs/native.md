# Native Spring AI MCP declarations

Date: 2026-09-12. The user explicitly requires native Spring AI MCP annotations and custom HTTP endpoint annotations to coexist behind the same identity, permissions, audit and applicable write-operation controls. This adapter builds declarations only; it does not create a second MCP server or bypass the central registry.

## Supported declaration path

The baseline is Spring AI 1.1.8 with `org.springaicommunity:mcp-annotations:0.9.0` and the synchronous stateless MCP SDK 0.18.3. `NativeDeclarations.scan(ListableBeanFactory)` is called after application singleton initialization; a `scan(Map<String, ?>)` variant accepts an already-collected set of actual Spring beans.

`NativeCatalog` returns `tools()`, `resources()`, `resourceTemplates()`, `prompts()`, and `completions()`. Each entry has `source()` and `specification()`. Source records retain `beanName()`, `bean()`, `targetClass()`, `method()`, and `origin()`. The registrar must wrap every callback before server registration and apply its capability-list filtering independently of invocation authorization.

| Input declaration | Official provider / result |
| --- | --- |
| `@McpTool` | `SyncStatelessMcpToolProvider` → `SyncToolSpecification` |
| `@McpResource` fixed URI | `SyncStatelessMcpResourceProvider` → `SyncResourceSpecification` |
| `@McpResource` URI template | Same provider → `SyncResourceTemplateSpecification` |
| `@McpPrompt` | `SyncStatelessMcpPromptProvider` → `SyncPromptSpecification` |
| `@McpComplete` | `SyncStatelessMcpCompleteProvider` → `SyncCompletionSpecification` |
| Explicit `ToolCallback` / `ToolCallbackProvider` bean | Callback definition/schema → stateless tool specification; exception propagation and unmodified text output |

Every annotated method is checked before passing it individually to the official provider, then checked to produce exactly one capability. The 0.9.0 providers normally log and skip reactive/bidirectional declarations; this adapter fails startup instead. It also rejects future/CompletionStage returns, unsupported native session/client callback annotations, multiple native declarations on one method, composed annotations the provider cannot directly interpret, and invalid/duplicate names or references. This makes unsupported capabilities visible during integration rather than silently disappearing.

Native methods must be public, non-static instance methods. Their class must retain compiler parameter names (`-parameters`) for model-supplied arguments. Servlet objects, Spring Security identities and bridge identity objects cannot be declared as model-bound native arguments. Use the trusted MCP transport context or the already established Spring Security context. Native resources, prompts and completions are read operations; explicitly marking them WRITE is rejected. A mutation belongs in a Tool so that the central write coordinator can apply preview/confirmation/idempotency.

## Preserve the actual Spring proxy

The original provider's method enumeration is adapted to read the target class, while its invocation bean remains the **actual Spring-managed object**. With class-based proxies, invoking that target Method on the proxy still enters Spring method security and other applicable advice. No target instance is extracted for invocation. Native JDK dynamic proxies are rejected with a diagnostic asking the host to enable class-based proxying; the adapter never falls back to an unwrapped object. Final methods on proxied classes are rejected because they would evade the proxy advice.

`@PreAuthorize`, `@Secured` and JSR-250 role/permit/deny rules remain on their original class/method. The registrar reuses their active Spring before-authorization interceptors for current permission checks, including access to prepared content and old receipts; it preserves host role-prefix and hierarchy configuration. A declared but inactive guard refuses registration. `@PreFilter`, `@PostFilter` and `@PostAuthorize`, including inherited/composed declarations, are rejected in this version because they cannot be faithfully reapplied to immutable previews and historical receipts. Use a shared business authorization policy or an explicit compatible facade while retaining the original protection.

The central registrar adds its trusted identity and capability rules around the same execution path, including resources/prompts/completions. Schema metadata and native `readOnlyHint` are descriptive, never proof of authorization or absence of effects. Domain data/field permissions remain in the host's shared business services.

Explicit callback beans are opaque execution declarations. `NativeSource.method()` is null for them; the adapter does not reflect into private callback fields or invent a business method. For provider-produced callbacks, the source bean is the declaring provider. The central policy layer must use explicit callback/name policy, and the host callback must call its managed business services. A callback is never made publicly callable solely because it exists. The same callback object exposed by a direct bean and a provider is deduplicated by identity; distinct callbacks claiming the same name are rejected.

Ordinary Spring AI `@Tool` methods are not discovered automatically. If a host deliberately provides a `ToolCallback`/provider for one, it enters the explicit callback path and receives the same outer authorization controls.

## Results, errors and lifecycle

Native annotation metadata/schema and value conversion remain with the baseline official providers. Ordinary native tool exceptions are configured to propagate to the central error/audit layer instead of being consumed by the provider's catch-all conversion. Explicit callbacks retain their original text, including a string that happens to contain JSON; this adapter does not reinterpret it as an application success envelope. Exceptions propagate, and an already-returned native `CallToolResult` retains its error status. Resource/prompt/complete providers may wrap exceptions; central error handling must sanitize those and must not expose stack traces or raw provider exception data.

This class does not establish identity, make a global security chain, set confirmation policy, publish capabilities, run tasks, or manage a second session. Those responsibilities belong to the shared registrar and transport. Discovery is a startup snapshot; adding beans dynamically requires an explicit registry refresh design and is not implemented here.

## Verification record

2026-09-12: the adapter was checked against the local **0.9.0 provider and callback source jars**, including their method scanning, proxy invocation, stateless filtering, and result handling. A targeted test suite has been added to run the actual official callbacks against a Spring context with method security: it checks denial and permitted reads across tools/resources/templates/prompts/completions, explicit callback failure/text behavior, and rejection of asynchronous/JDK-proxy/duplicate/write-resource declarations. It does not use a business database.

The initial source-only status was superseded later on 2026-09-12 after integration compilation was authorized. The latest core was installed locally, `mvn -f mcp-bridge-spring-boot-starter/pom.xml compile -DskipTests` passed, and `mvn -f mcp-bridge-spring-boot-starter/pom.xml -Dtest=NativeAuthorizationTest test` passed **all 3 tests with 0 failures and 0 skips**. The five native capability callbacks were actually invoked through the Spring method-security proxies in the authorized/unauthorized scenarios. Two SDK integration corrections were required: explicitly pinning `mcp-spring-webmvc` to 0.18.3 because that dependency was not version-managed by the imported Spring AI BOM, and using the SDK completion specification's `referenceKey()` accessor in the central runtime.

These are isolated integration results. File-module tests are documented separately in [files.md](files.md). No native production/client acceptance, remote publication, commit or deployment is claimed.
