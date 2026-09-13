# Uploads and binary exports

Date: 2026-09-12. Scope: independent file module supporting uploads and exports. This module does not import downstream business code, access a business database, define a security filter chain, or fetch arbitrary URLs.

## Runtime entry points

Add `com.cogistra:mcp-bridge-files` and provide the host `BridgeIdentityResolver`. `BridgeFilesAutoConfiguration` installs the default `LocalBridgeFileStore`, `FileUploadService`, and servlet `BridgeFileController` when their prerequisites are available. The controller resolves the identity provider at request time to avoid auto-configuration ordering dependence; without a resolver it rejects access with 401. `mcp.bridge.files.enabled=false` disables this auto-configuration. The calling application must authenticate HTTP requests before the resolver receives `Authentication`; a missing/anonymous identity is rejected even if the host accidentally permits the URL.

| HTTP action | Contract |
| --- | --- |
| `POST /mcp-bridge/files` | Multipart field `file`; returns `FileArtifact`. The input stream is closed by the store, including failures. |
| `GET /mcp-bridge/files/{fileId}` | Revalidates the caller, checks ownership/expiry, streams the bytes, and closes the stored input stream. Uses attachment disposition, `nosniff`, `Cache-Control: no-store`, and sandbox CSP. |
| `DELETE /mcp-bridge/files/{fileId}` | Checks the same identity and deletes the owned artifact; returns 204. |

The URL is an authenticated download location, not a public bearer link. Ownership requires the same issuer, subject **and OAuth client**. A second client logged into the same account cannot reuse the first client's file ID. Storage byte quotas are aggregated by issuer+subject across clients so switching clients cannot multiply the allowance. Cross-owner access, invalid IDs and expired files all return `FILE_NOT_FOUND`; HTTP is 404. HTTP anonymous requests receive 401. The host's CSRF rules still apply to cookie-authenticated upload/delete; this module neither disables CSRF nor duplicates login.

The ID is a random 128-bit UUID encoded as 32 lowercase hex characters. User filenames only affect display metadata; path components and control/reserved filename characters are normalized. They never select disk locations. Returned media types are parsed and bounded. HTML downloads remain attachments; the store does not claim to scan file content for malware.

## MCP chunk workflow

The MCP registrar calls `com.cogistra.mcpbridge.files.FileUploadService` with its server-verified identity; these methods do not accept identity fields from tool arguments:

1. `begin(identity, filename, mediaType, totalBytes, sha256)` returns `UploadSession(uploadId, totalBytes, maxChunkBytes, expiresAt)`. It requires the whole-file SHA-256 and reserves declared bytes against quotas before accepting content.
2. `append(identity, uploadId, offset, byte[])` returns `UploadProgress(uploadId, receivedBytes, totalBytes)`. Chunks must be sequential. A retry wholly inside committed bytes succeeds only if its content exactly matches; gaps, partial overlaps or changed content fail.
3. `complete(identity, uploadId)` checks exact length and final SHA-256, then publishes a `FileArtifact`. Calling complete again during the upload session lifetime returns the same artifact while it remains available.
4. `cancel(identity, uploadId)` removes an unfinished upload. A completed artifact is deleted through `BridgeFileStore.delete` instead.

Session metadata and committed chunk bytes survive application restarts. Each append writes a bounded replacement staging file and atomically replaces the prior committed file after forcing the written bytes; interrupted staging work does not become a half-committed chunk. Completion reconciles its persisted artifact record if interrupted after metadata publication. This is local-file crash recovery, not a claim of distributed transactional storage or power-loss durability on every filesystem. A client whose session expires must begin again.

The registrar is responsible for limiting encoded JSON/base64 input before decoding it, then applying its ordinary MCP identity, authorization, audit and input validation path. This service limits decoded chunk bytes. Receiving or deleting an attachment alone does not perform the associated business action. The original business endpoint must still check order responsibility, file applicability, version, preview/confirmation and other domain restrictions.

## Adapting HTTP endpoints

- `FileAdapters.multipart(store, identity, fileId)` returns a production `MultipartFile` without using spring-test. Every `getInputStream()` reacquires the artifact and rechecks its expiry/ownership. The consumer closes each returned stream. `getBytes` and `transferTo` close their streams internally. A destination supplied by the trusted business endpoint is an ordinary server-side destination; never bind a caller's local path into `transferTo`.
- `FileAdapters.fromResponseEntity(store, identity, ResponseEntity<byte[]>)` retains filename and MIME metadata and stores successful binary results. Failed HTTP status is an export error. The store still bounds the content; a byte-array-producing endpoint has already allocated that array before the adapter can inspect it.
- `new CapturingFileResponse(maxBytes)` implements `HttpServletResponse` directly. Pass it only to the selected endpoint's response argument. It retains status/headers and bounds output bytes independently of the real MCP response. Call `finish(store, identity, defaultFilename)` to obtain the artifact. Writer/output-stream exclusivity and reset/commit semantics are enforced. Async servlet listeners are deliberately rejected; this class is for synchronous exports. It does not forward cookies, redirects or arbitrary HTTP headers to the MCP connection.
- A business endpoint that already returns an authorized export URL can keep that result under the caller's explicit output contract. This module does not fetch that URL or convert a local client path into a server path.

## Configuration and persistence

All values below use `mcp.bridge.files.*`, are read on application startup, and match `BridgeFilesProperties`:

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Activate file module auto-configuration. |
| `directory` | `.local/mcp-bridge/files` | Persistent directory relative to process working directory; production should configure a stable absolute mounted directory. |
| `max-file-bytes` | 16777216 | Maximum decoded artifact/upload size, 16 MiB. Must be positive and no larger than Integer.MAX_VALUE because the synchronous export capture is bounded in memory. |
| `owner-quota-bytes` | 134217728 | 128 MiB per issuer+subject, including unfinished declared upload reservations. |
| `total-quota-bytes` | 1073741824 | 1 GiB across stored artifacts and upload reservations. |
| `max-files` | 4096 | Global logical item count limit. |
| `max-owner-files` | 128 | Per-owner logical item count limit. Completed session tombstones whose file was deleted still consume a slot until session expiry. |
| `max-chunk-bytes` | 262144 | 256 KiB per decoded chunk, no larger than the file limit. The starter verifies that base64 and metadata fit its JSON argument budget. |
| `ttl` | `24h` | Artifact lifetime from successful creation/completion. |
| `upload-ttl` | `1h` | Upload session lifetime from begin, never extended by appends. |
| `cleanup-interval` | `5m` | Background expired-file/session cleanup period. Cleanup also runs on startup and before new stores/uploads. |
| `download-url-prefix` | `/mcp-bridge/files` | Artifact URL prefix. Configure `/api/mcp-bridge/files` or an HTTPS external prefix when a context path/reverse proxy adds a prefix. This does not alter the controller mapping. |

The servlet container also has multipart limits. Configure `spring.servlet.multipart.max-file-size` and `max-request-size` to the desired HTTP allowance; the container may reject a file before the component reads it. The component does not override host multipart configuration. The store and MCP upload limits remain independently enforced.

The root directory is checked for symbolic-link ancestors, and internal files use strict generated names and no-follow checks. POSIX directories receive owner-only permissions; on Windows, configure an equivalent service-account ACL. **Other untrusted local processes must not have write access to the directory or its parent.** Application-level path checks cannot promise protection against a privileged local actor replacing parent directories concurrently. The default store retains an exclusive process lock and refuses a second process opening the same root. Use separate roots or a distributed storage implementation for multi-instance deployments; do not share this default through an unverified network filesystem.

The directory contains artifact metadata/bytes and upload metadata/parts. Expired sessions, expired artifacts and abandoned staging files are removed without following symbolic links. Opened downloads may continue until their current stream closes; expiry prevents new opens. On Windows an open handle can temporarily prevent deletion, and the next cleanup retries. Quotas describe logical committed/reserved content; atomic chunk replacement temporarily needs additional physical disk space up to one file's size, so provide free-space headroom. Cleaner errors do not delete unknown business files; subsequent accesses continue checking ownership and expiration.

`BridgeFileStore` is the replaceable artifact SPI. Supplying a host bean disables the default local store. The default persistent chunk service is coupled to the local store; an external/cloud store integration must supply its complete upload workflow rather than silently mixing cloud artifact reads with local upload IDs. This is an explicit extension boundary of this first implementation.

## Verification and status

2026-09-12: `mvn -f mcp-bridge-files/pom.xml test` completed with **7 tests: 6 passed, 1 skipped**. The skipped scenario attempts to create a real symbolic link; this Windows host does not permit creating it. The passing scenarios use actual temporary files for ownership/client isolation, input-stream closure, restart persistence, reservation quotas, expiry, chunk sequencing/retry/hash failures, completion replay, multipart conversion, and Servlet/ResponseEntity binary export. The HTTP scenario goes through a real Spring DispatcherServlet in MockMvc for multipart upload, authenticated/anonymous/cross-owner download and deletion plus response headers. It is not a real network or production browser acceptance.

The standalone sample also passed real loopback HTTP/MCP chunk calls, repeated append/completion, authenticated download and deletion using OAuth-issued JWTs on Java 17; see [verification](verification.md). The module compiles and was installed in the local Maven repository for integration work. No commit, remote artifact publication, business database access or deployment occurred. Remaining acceptance includes the selected host identity/CSRF configuration, the host's actual binary export endpoints, mounted-disk permissions and Linux symbolic-link behavior, reverse-proxy URL prefixes, external MCP clients, and any distributed/cloud storage replacement.
