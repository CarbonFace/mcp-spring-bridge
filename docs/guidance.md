# Server-maintained business skills

## 2026-09-15 runtime versions

Hosts may now publish immutable instruction versions from their own persistent storage without restarting the MCP server. This is an opt-in extension: existing `BridgeGuidanceProvider` assets are still read once at startup, and applications without providers still omit the guidance tool. The library does not implement a host's publication endpoint, permissions, database, human confirmation, deployment or client installation.

The implementation was developed on `codex/knowledge-runtime-guidance`, based on `df698bd`, and is included in the 2026-09-16 source delivery through the default `master` branch. Obtain and install that source before rebuilding a host. This is not a published Maven artifact or a deployed business feature. See [release instructions](releasing.md) and [verification](verification.md#2026-09-15-动态知识快照与指定版本读取) for delivery and the executed checks.

```java
public interface BridgeDynamicGuidanceProvider extends BridgeGuidanceProvider {
  default List<BridgeGuidance> guidance() { return List.of(); }
  List<BridgeGuidanceDescriptor> descriptors();
  BridgeGuidanceVersion resolveVersion(String skillId, String requestedVersion);
  BridgeGuidance load(String skillId, BridgeGuidanceVersion version);
}

public record BridgeGuidanceDescriptor(
    String id, Set<String> requiredTools, int maxSourceBytes) {}

public record BridgeGuidanceVersion(String version, String bundleSha256) {}
```

`descriptors()` registers stable skill identifiers and required tool names at startup. These descriptors make `bridge_get_guidance` available even when the dynamic host has not published its first release. Dynamic and static providers may be mixed; identifiers must be globally unique. A dynamic provider may also return additional static definitions from `guidance()`, provided their identifiers do not collide.

Each authorized request calls `resolveVersion` before using any cached content. `requestedVersion == null` means the host's current published pointer; otherwise it means exactly the specified version. The host must check current baseline compatibility/existence on every resolution, including historical versions, and throw a safe `BridgeException` when unavailable. A resolver returning a different version for an explicit request is rejected with `GUIDANCE_VERSION_UNAVAILABLE`; the registry never falls forward to the latest version. Do not implement the current pointer as a stale process-local cache when several host instances share published storage.

`load` returns the exact immutable `BridgeGuidance` associated with that version and ZIP digest. Its identifier, version, required tools, reserved source size, UTF-8 and ZIP SHA-256 are verified before the compiled snapshot can enter the cache. A corrupt/mismatched load returns `GUIDANCE_SNAPSHOT_INVALID`; changing bytes for an existing version is a host storage error, not a supported update. The host must persist immutable history and select a new version when files or deployment-specific settings change. The registry additionally rejects a different hash for the same version while that version is cached; persistence is the host's responsibility, not an unbounded library history ledger.

`BridgeGuidanceSnapshot.of(definition)` is the common compiler for publishers and readers. It exposes `definition()`, `sourceBytes()`, `version()`, `bundleSha256()` and a defensively copied `bundleBytes()`. A host can persist these exact bytes and digest before atomically switching its published pointer. ZIPs use the same deterministic algorithm as static guidance. Two skill definitions with identical complete file maps produce identical ZIP bytes/hash even when their IDs and entrypoints differ. The library does not modify historical endpoint URLs or plugin manifest versions during later reads.

Dynamic registration reserves `maxSourceBytes` for each declared skill, between 1 byte and 2 MiB. The sum of dynamic reservations and static actual source bytes must fit the existing 8 MiB registration limit; the combined count remains at most 64 skills. Every loaded version must fit its own reservation and the existing 128-file, 256 KiB/file and 2 MiB/skill limits. This reservation avoids temporarily empty providers or later larger releases bypassing the catalog limit. For two skills sharing a 2 MiB bundle, reserve 2 MiB for each, just as static registration counts each definition's assets.

The compiled dynamic cache is an access-ordered cache limited to 64 snapshots and 8 MiB of source bytes, keyed by skill ID, version and ZIP digest. Old entries are evicted and can be loaded again from host history. The cache also holds bounded derived text and ZIP bytes, so its source-byte limit is not a claim of exact JVM heap usage. Static snapshots retain their original registration behavior. No authorization outcome, current pointer or baseline compatibility result is cached. Required tool visibility and provider `available()` run on each catalog/body/section/bundle request, including cache hits.

### Version-aware client reads

The existing four request forms below remain supported. Add `"version":"<returned-version>"` to a body, section or bundle request to fix the selected release. A version without `skillId` is invalid. A static skill accepts its registered version and rejects any other version. Every response is internally consistent; clients omitting the version keep request compatibility but must compare returned versions and re-read if they need consistency across several calls. Already-loaded conversation text and personally installed files do not change automatically when the server publishes a release.

If an authorized dynamic skill is not initialized, incompatible or otherwise returns a safe `BridgeException`, its direct read returns that error. A catalog response lists other usable skills normally and adds `unavailableSkills: [{id, code, message}]` for the affected authorized entries. This lets a separately registered static maintenance guide remain discoverable. When there are no such errors the response remains `{"skills":[...]}`. Denied/hidden skills do not appear in either list, and availability exceptions still fail closed.

### Host rollout and recovery

First install the updated component locally and rebuild the host; existing static providers need no configuration or database changes. A host enabling dynamic maintenance must separately implement durable publication, atomic pointer switching, confirmation, compatibility, backup and limits. Registration reserves resources but does not read or initialize a host database. Runtime resolution and loading occur on demand. A host restart must read its existing published pointer rather than overwrite storage from packaged assets; this behavior cannot be enforced by the generic component.

Roll back a host integration using its compatible release and stored version history. A business publication rollback selects a new immutable host release; it is not deletion of history or rebuilding the entire MCP server. A temporarily unavailable current version returns the host's safe error and does not silently substitute an old static package. File export still uses the existing private owner/client/TTL/quota mechanism, and export failure does not undo the host's already-committed publication.

## 2026-09-13 decision and behavior

The host maintains Skill instructions in its repository and packages that same source with its application. Previously the starter had no instruction catalog or asset distribution extension. It now discovers `BridgeGuidanceProvider` beans and publishes one read tool, `bridge_get_guidance`, when at least one valid Skill is registered. Applications without providers retain their previous tool set and initialization instructions.

An authenticated employee can discover applicable instructions, read the full selected Skill and its references, then use the existing business tools. Reading instructions, staging a downloadable bundle and installing a client plugin are not business confirmation. Every business write still needs its complete current server review and a new explicit user confirmation. Instructions cannot attest to a human chat reply or replace backend authorization.

The MCP `initialize.instructions` field retains the mandatory write/retry/file guidance and appends a short navigation pointer only when `bridge_get_guidance` is enabled. Its tool description contains the same navigation for clients that ignore initialization instructions. No long business manual is inserted into initialization. Client selection and consumption of instructions still require real-client acceptance.

## Host extension

All types are under `com.cogistra.mcpbridge.guidance` in the starter:

```java
public interface BridgeGuidanceProvider {
  List<BridgeGuidance> guidance();
  default boolean available(String skillId, BridgePrincipal principal) { return true; }
}

public record BridgeGuidance(
    String id, String title, String description, String version,
    String entrypoint, Map<String, byte[]> files, Set<String> requiredTools) {}
```

`guidance()` runs at application startup. Supply trusted, explicitly enumerated build/classpath assets, never customer remarks, an arbitrary filesystem directory or a client-provided URL. The host owns business applicability, employee/account rules, Skill content/version and packaging. `available()` runs on each catalog, body and bundle request under the current verified identity; exceptions fail closed for that entry. Current permissions for each `requiredTools` name are also checked against the public tool catalog. Use actual published names such as `records_edit_prepare`, not an unexposed raw write capability.

The generic tool remains discoverable to identities allowed by its ordinary system policy when their applicable catalog is empty; their catalog returns `{"skills":[]}`. Direct reads or exports of unknown/denied skills return the same controlled `GUIDANCE_UNAVAILABLE` result. `mcp.bridge.tools.bridge_get_guidance` can restrict scopes/authorities or disable this optional system tool using the existing configuration mechanism. A disabled tool does not contribute the initialization pointer. There is no new business role, account store or authorization bypass.

For static providers, use one source directory for both runtime classpath assets and optional locally distributed plugin sources. Updating those packaged assets requires rebuilding/restarting the host. Neither provider mode watches arbitrary files or remotely installs client instructions. Change the content version when its files change. SHA-256 hashes identify the actual bytes, including any deployment-specific metadata the host deliberately produces. The opt-in runtime provider described above supplies published versions without replacing this static behavior.

## One tool, four requests

| Arguments | Result |
|---|---|
| `{}` | `skills` catalog, each with `id`, `title`, `description`, `version`, `entrypoint`, `bundleSha256`, `bundleAvailable` |
| `{"skillId":"records-edit"}` | Full entrypoint text in `content`, plus `skillId`, `version`, `bundleSha256`, `section`, `sections`, `bundleAvailable` |
| `{"skillId":"records-edit","section":"skills/records-edit/references/rules.md"}` | Full text of exactly that registered readable path; same metadata |
| `{"skillId":"records-edit","format":"bundle"}` | `skillId`, `version`, `bundleSha256`, and `artifact` with existing `FileArtifact` fields |

`format:"text"` may be used with a skill/section. A section or format without `skillId`, a bundle with a section, unknown fields and unsupported formats are rejected. `sections` lists every registered `.md`, `.txt`, `.json`, `.yaml` and `.yml` asset with exact `path`, SHA-256 `sha256`, and byte count `bytes`; these must contain valid UTF-8 without NUL. The entrypoint is an included `SKILL.md`. Other registered binary assets are bundled but not returned as text. A reference path is an allowlisted identifier, not a request to access server/client files or a URL.

Every response includes complete JSON in normal MCP text content as well as structured content. Clients that only consume text therefore receive the actual instructions. Oversized results fail under the common result limit; instructions are never silently truncated. Runtime field schemas, defaults, business policies and permissions should continue to come from the host's existing context/query tools, not a second hardcoded rule engine in the Skill.

## Asset and download boundaries

Startup validates portable relative paths, missing entrypoints, case-insensitive duplicates, file/directory collisions and bounded registration. Absolute paths, drive names, backslashes, `.`/`..` segments and Windows device names are rejected. Asset maps and bytes are defensively copied. Fixed limits for this first version are 128 files per Skill, 256 KiB per file, 2 MiB total source bytes per Skill, 64 registered Skills and 8 MiB total registered source bytes. Metadata is bounded to 64-character identifiers/versions, 160-character titles, 1024-character descriptions and 240-character paths. These registration limits are constants, not configuration settings.

ZIP exports contain all registered files in sorted path order with stored entries and fixed local DOS timestamp `2000-01-01T00:00`; content and hashes are reproducible across timezones. The advertised `bundleSha256` hashes the complete ZIP bytes. Download requests create a temporary private artifact through the existing `BridgeFileStore`, with its owner/client checks, TTL, quotas, file count and file-size limits. `mcp.bridge.max-export-bytes` additionally bounds the ZIP. Existing file defaults and storage requirements are documented in [files](files.md). `bundleAvailable` reflects file-support presence and the export-size limit, not a reservation of free quota. Quota exhaustion may still reject an export.

When file support is disabled, online catalog and text reads still work, `bundleAvailable` is false, and export returns `FILES_DISABLED`. The server does not automatically install anything on an employee device. A supported client may explicitly import/install a downloaded plugin or Skill through its own mechanism. The file download URL remains authenticated; it is not an anonymous permanent distribution URL.

Permission changes immediately affect subsequent guidance requests. An already-created artifact uses the inherited current-token/owner/client/expiry file boundary; it is not re-bound to the Skill provider's business-role check. Previously downloaded or already-read instructions cannot be recalled by revoking a business role. Do not place secrets or employee-specific business data in shared instruction assets.

## Verification and delivery status

See the dated entry in [verification](verification.md) for actual commands and results. Isolated Servlet/SDK tests use synthetic accounts, in-memory fixture facts and temporary private files. They cover catalog/body/reference text, equal structured/text results, archive completeness/hash, denied reads/downloads, current permissions, malformed arguments, default no-provider compatibility and source/path bounds. The asset scenario also checks cross-timezone reproducibility and file-disabled online reads.

No database table or migration is added. The source delivery and local Maven installation are recorded in [release instructions](releasing.md); no remote Maven artifact, deployment, employee installation or real GPT acceptance is claimed. Confirming actual automatic Skill discovery, employee consent behavior and supported client installation remains a separate client acceptance step.
