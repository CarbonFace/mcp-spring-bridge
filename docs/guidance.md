# Server-maintained business skills

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

Use one source directory for both runtime classpath assets and optional locally distributed plugin sources. Updating content requires rebuilding/restarting the host; this version does not watch files or remotely hot-install instructions. Change the host version when content changes. SHA-256 hashes identify the actual bytes, including any deployment-specific metadata the host deliberately produces.

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

No database table or migration is added. Source and local Maven artifacts only: no commit, remote publication, deployment, employee installation or real GPT acceptance is claimed. Confirming actual automatic Skill discovery, employee consent behavior and supported client installation remains a separate client acceptance step.
