# MCP Spring Bridge

A Cogistra library for exposing selected Spring MVC endpoints and Spring AI MCP declarations through one authenticated MCP server.

**Status:** `0.1.0-SNAPSHOT`, verified locally and on GitHub with Java 17/21 on Linux/Windows, including the initialization notification fix. The artifacts are not published to Maven Central. The repository is licensed under [MIT](LICENSE), copyright Cogistra. See [verification](docs/verification.md) for evidence and remaining acceptance work.

Source repository: [CarbonFace/mcp-spring-bridge](https://github.com/CarbonFace/mcp-spring-bridge), public, default delivery branch [`master`](https://github.com/CarbonFace/mcp-spring-bridge/tree/master). This branch includes initialization fix `f66a7c410ed0ec45ac41192944bd6e53c8c5356a`; the earlier `codex/*` branches remain as development history. Repository access and Maven artifact publication are separate; builders currently install the pinned source locally before building downstream applications. See [release instructions](docs/releasing.md).

## What it provides

- Explicit `@McpEndpoint` registration on existing controller methods, with optional MCP-specific input and output contracts.
- Native Spring AI `@McpTool`, `@McpResource`, `@McpPrompt` and `@McpComplete` support through the same identity and authorization boundary.
- Spring method security on the actual application proxy, additional host policies, caller-specific discovery and minimal audit queries.
- Prepared writes with immutable execution snapshots, revision/hash confirmation, durable receipts and conservative recovery after ambiguous failures.
- An explicit host confirmed-write runtime extension for applications that commit a business action and its durable receipt in one database transaction, while retaining framework-generated tools and the fixed secured controller invocation.
- Authenticated multipart and chunked uploads, reusable file references, ordinary URL results, and binary/Servlet-response exports.
- Server-maintained Skill catalogs, progressive instruction reads and optional private bundles from the same registered host assets.
- An optional OAuth authorization module connected to a host account SPI, plus an independent application with synthetic accounts.

The library does not contain a business database, employee roles or domain-specific permissions. Applications continue to own their data scope, field permissions, optimistic versions, transactions and workflow rules.

## Baseline

| Component | Initial baseline |
|---|---|
| Java | 17 language/runtime baseline; local verification on JDK 17.0.19, with earlier targeted runs on 21.0.11 |
| Spring Boot | 3.5.14 |
| Spring AI | 1.1.8; native annotations from `org.springaicommunity.mcp.annotation` |
| MCP Java SDK | 0.18.3 |
| JSON Schema validator | networknt 2.0.0 |
| Transport | Synchronous, stateless Streamable HTTP over Spring MVC |

Spring Boot 4 / Spring AI 2, WebFlux and bidirectional session callbacks are not claimed as supported by this version. Unsupported bindings fail during registration instead of becoming untrusted tool inputs. See the [contract and compatibility guide](docs/controller-contracts.md).

## Build and try it

Use Maven 3.8.8 or later and a supported JDK:

```shell
git clone https://github.com/CarbonFace/mcp-spring-bridge.git
cd mcp-spring-bridge
git rev-parse HEAD
mvn install
```

Record the source commit for reproducible downstream builds. `mvn install` runs isolated tests and installs the component artifacts into this machine's configured Maven repository. Another build machine must install the same source before it can resolve the dependency; the coordinates alone cannot download an unpublished artifact. An existing checkout on `codex/mcp-controller-adapter` must explicitly switch to `master` after protecting local changes.

Tests use temporary files, synthetic identities and local servlet applications. They require no company account, business database or deployed server. The sample is excluded from Maven installation/deployment; its source remains in this repository.

Follow the [standalone sample guide](docs/sample.md) to initialize a private development directory, provide your own demo passwords, start the application and complete the OAuth-to-MCP flow. No production signing keys or reusable login credentials are embedded.

## Add it to a project

After building locally, add:

```xml
<dependency>
  <groupId>com.cogistra</groupId>
  <artifactId>mcp-bridge-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Provide a verified identity and a permission policy, enable Spring method security, and explicitly annotate the endpoints you want to expose:

```java
@GetMapping("/records/{id}")
@PreAuthorize("hasAuthority('records:read')")
@McpEndpoint(
    name = "records_get",
    description = "Read one record owned by the current account",
    effect = Effect.READ,
    output = RecordView.class)
public RecordView get(@PathVariable String id) {
    return records.getOwned(id); // The shared business service enforces ownership.
}
```

Existing HTTP clients keep using the controller. MCP invokes the actual Spring bean through the adapter; it does not send an internal loopback HTTP request or unwrap the target object. MVC filters, handler interceptors and application-specific error envelopes need explicit integration as described in the contract guide.

For a declared write `records_edit`, clients receive `records_edit_prepare` and `records_edit_submit`. Preparation returns the full original input and resolved execution snapshot. The client must show that preview and obtain explicit user confirmation before submitting its operation ID, revision and hash. A changed preview needs new confirmation. The server verifies the snapshot and current permissions; it cannot independently prove what was said in a client conversation.

Projects with an existing authorization server provide `bridgeJwtDecoder` and `BridgeIdentityResolver`. Projects with only an account/password system may also add `mcp-bridge-authorization-server` and implement its account SPIs. See [configuration](docs/configuration.md) and [authorization](docs/authorization.md).

## Documentation

- [Controller contracts, native compatibility and extension points](docs/controller-contracts.md)
- [Configuration and host integration](docs/configuration.md)
- [Native Spring MCP declarations](docs/native.md)
- [Uploads and exports](docs/files.md)
- [Write confirmation, recovery and audit](docs/operations-audit.md)
- [Host confirmation runtime and transaction responsibilities](docs/host-confirmation-runtime.md)
- [Server-maintained business skills and optional bundles](docs/guidance.md)
- [Optional OAuth authorization server](docs/authorization.md)
- [Standalone sample](docs/sample.md)
- [Verification evidence](docs/verification.md)
- [GitHub and Maven release preparation](docs/releasing.md)
- [Changelog](CHANGELOG.md), [contributing](CONTRIBUTING.md), [security reporting](SECURITY.md)

Local persistence uses private directories and one owning process. A clustered deployment must replace the relevant storage SPIs and define authoritative business recovery. A local operation receipt and an unrelated business transaction are not an atomic, exactly-once commit.
