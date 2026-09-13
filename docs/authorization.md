# Optional OAuth authorization server

Updated 2026-09-12. The first component release includes this optional module, following the user's decision to reuse each application's existing accounts. It does not create business accounts, import a business system, or use any business database.

**The bundled encrypted file store is for one application process on one local durable filesystem. It is not a cluster store, network-share store, or an in-memory production default.** Multiple processes using the same directory are rejected. A distributed deployment must provide the `BridgeAuthorizationStore` contract described below. Nothing in this module performs a database migration.

## Add and enable

Add `mcp-bridge-authorization-server` at the same version as the bridge starter. The authorization module is disabled unless `mcp.bridge.authorization.enabled=true`. Enabling it requires explicit issuer, resource, registered clients, host account adapters, persistent private signing key, and persistent authorization storage. Missing or unreadable required configuration fails startup; it never creates an anonymous MCP connection or an ephemeral production signing key.

```yaml
mcp:
  bridge:
    authorization:
      enabled: true
      issuer: https://service.example/mcp-bridge/oauth
      resource: https://service.example/mcp
      brand: Example Service
      signing-jwk: file:/opt/example/secrets/mcp-signing.jwk
      storage-key: file:/opt/example/secrets/mcp-storage.key
      storage-directory: /var/lib/example/mcp-authorization
      access-token-ttl: 24h
      refresh-token-ttl: 7d
      authorization-ttl: 30d
      browser-session-ttl: 8h
      clients:
        - id: desktop
          name: Example Desktop
          redirect-uris:
            - http://127.0.0.1:64599/callback
          scopes:
            - orders:read
            - orders:write
```

`issuer` and `resource` are absolute HTTPS URLs without query, fragment, or user information. The issuer must not end in `/`. Explicit `development-loopback=true` additionally permits HTTP issuer/resource on `localhost`, `127.0.0.1`, or `::1`; it does not allow HTTP on arbitrary hosts. Native callback registration supports HTTPS or HTTP loopback. SAS performs exact redirect matching, with the standard dynamic-port exception for literal loopback IP addresses. Custom URI schemes and dynamic client registration are not exposed in this release. Register each client through configuration; there is no administration frontend.

The bridge starter's resource/audience and authorization-server configuration must agree with these values. When using the bundled suite, use its `resource` and `issuer` as the authoritative values rather than maintaining conflicting copies.

All durations must be positive and at most 366 days; access and refresh lifetimes cannot exceed the absolute authorization lifetime. Defaults are the user-confirmed **24 hours / 7 days / 30 days**. Each newly rotated refresh token receives up to 7 days; all tokens are capped by the original authorization's absolute expiry. Refresh does not extend that absolute deadline. Authorization codes expire after 5 minutes; pending consent requests expire after 10 minutes. Configuration changes take effect after application restart. Changing a client's scopes, callbacks, or protected resource makes its previous grants unusable; changing its display name does not.

## Reuse the host account system

The normal integration supplies two beans:

```java
@Bean
HostPasswordVerifier bridgePasswords(ExistingAccountService accounts) {
    return (username, password) -> accounts.verifyExistingPassword(username, password)
        .map(account -> new HostAccount(
            account.stableId(), account.username(), account.displayName(),
            account.enabled(), account.securityVersion(), account.currentAuthorities()));
}

@Bean
HostAccountDirectory bridgeAccounts(ExistingAccountService accounts) {
    return subject -> accounts.findCurrentByStableId(subject)
        .map(account -> new HostAccount(
            account.stableId(), account.username(), account.displayName(),
            account.enabled(), account.securityVersion(), account.currentAuthorities()));
}
```

The example's `ExistingAccountService` is a host adapter placeholder, not an API provided by the bridge. The host retains password hashing, account lookup, MFA/lockout policy where needed, and authority semantics. Neither plaintext passwords nor password hashes are stored in authorization records. The password adapter receives a temporary `char[]`, which the bridge clears after verification; adapters must not retain it.

`HostAccount.subject` is immutable across account renames and must not be reassigned to another person. `version` changes whenever credentials, permissions, or account enabled state change. The directory returns current enabled status, version, and authorities, not a stale login snapshot. Disabled/deleted accounts and changed versions stop both access-token use and refresh. The module rechecks the directory after successful password verification and again on token use; directory failures do not grant access.

`HostAccountDirectory.normalizeUsername` defaults to trimming surrounding whitespace. It is shared by password verification and login limits. Override it with the host's **idempotent, non-null canonicalization** when usernames are case-insensitive or aliases are equivalent; do not normalize only inside the password verifier and leave the limiter with a different key.

For a host that already has a suitable `AuthenticationProvider`, supply a bean named `bridgeBrowserAuthenticationProvider`. Its successful `Authentication` must contain the just-verified `HostAccount` as its principal. The bridge rereads that subject and rejects a changed version, then creates its own browser authentication. The default provider is constructed only for this browser chain and is not published as a global authentication provider. Other host providers are not automatically treated as MCP identity providers.

## Protocol and identity boundaries

Endpoints are fixed under `/mcp-bridge/oauth`:

| Endpoint | Behavior |
| --- | --- |
| `GET /authorize` | Registered public client, authorization code, mandatory S256 PKCE and exact resource |
| `POST /authorize` | Authenticated consent with CSRF; omitting all scope values declines |
| `POST /token` | Code exchange or rotating refresh; form body, single parameters, no query credentials |
| `POST /revoke` | Token possession plus registered public client; invalidates the matching grant |
| `GET /jwks` | Public signing keys only |
| `GET/POST /login` | Host account login, browser session fixation protection, CSRF |
| `GET /consent` | Review pending request belonging to the logged-in subject and client |
| `GET /grants` | Current subject's active connections, 25 per page |
| `POST /grants/revoke` | Current subject's own connection only, CSRF |
| `POST /logout` | Sign out of the bridge browser session, CSRF |

Client authentication is `none`. PKCE verifiers remain with native clients; client identifiers are public, not passwords. Only configured scopes are offered. Consent occurs for every new connection; previous consent is not silently reused. A granted scope never replaces host business permissions or the starter's per-operation write confirmation.

The authorization request must contain `resource` equal to the configured resource. Code exchange and refresh may omit it because the saved authorization already fixes the resource; a supplied value must match exactly. Every JWT has that single audience, the configured issuer, stable subject, client ID and granted scopes. No token-forwarding or arbitrary resource proxy is provided.

SAS implements authorization code consumption, PKCE, consent and token endpoint responses. The bridge supplies the public-client refresh/revocation adapter, rotating refresh generator, account/policy checks and durable grant history. This distinction matters: the [official SAS PKCE guide](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html) documents that its default public-client configuration does not issue refresh tokens. Public native refresh support here is an explicit component extension, not a claim about SAS defaults. The implementation was checked against the locally resolved SAS **1.5.7** sources supplied by Boot **3.5.14**; the linked rolling reference may show a newer maintenance release.

Each refresh replaces both the refresh token and the current access token. Clients must switch to the returned access token immediately. Reusing a retired refresh token with its own client ID revokes the entire grant. A different client cannot use a retired token to revoke somebody else's grant. Browser revocation and protocol revocation invalidate current access and refresh tokens immediately for subsequent validations. A request already admitted before revocation is not rolled back. Hashes of retired refresh tokens remain attached to their grant until its absolute expiry; a grant is capped at 4,096 rotations and then requires new authorization.

The named `bridgeJwtDecoder` verifies signature, issuer, audience, current persisted access token, absolute expiry, enabled account and account version. A host may replace this bean **by name**. The provided `BridgeIdentityResolver` is conditional on no resolver of that type and maps a verified `JwtAuthenticationToken` to `BridgeIdentity` plus a new authentication carrying **current host authorities**. A replacement resolver is the host's explicit security integration point; a project using an external identity provider can supply both resolver and named decoder without enabling this authorization-server module.

The authorization module's chains cover only its OAuth paths and exact metadata paths. It does not own `/mcp`, files, audit APIs, or the host's global HTTP authorization. The starter owns resource protection and uses the named decoder explicitly. Browser context, request cache and CSRF use separate session keys. Logout and stale-login cleanup remove those bridge keys while preserving unrelated host session attributes. Host session-cookie configuration and host security chains still need to be correctly configured by the application.

## Discovery, browser security and hosting

For `issuer=https://service.example/mcp-bridge/oauth`, authorization metadata is served at:

`https://service.example/.well-known/oauth-authorization-server/mcp-bridge/oauth`

For `resource=https://service.example/mcp`, protected-resource metadata is at:

`https://service.example/.well-known/oauth-protected-resource/mcp`

Metadata names the configured external issuer/resource and fixed OAuth endpoint URLs, independent of the incoming Host header. No dynamic request headers are used to select a different issuer. The normal deployment uses a root servlet context. If deploying under a servlet context or reverse-proxy path prefix, the proxy must publish these exact origin-level well-known URLs and map the fixed OAuth endpoints consistently; changing only an external issuer URL does not add servlet routes. Verify the actual externally published URLs and callbacks before accepting that deployment.

Pages are fixed classpath Thymeleaf templates with cached template parsing. Brand, account/client names, state and scopes are escaped through text/attribute bindings; they cannot select or modify templates. Forms submit to the same backend. Approval and decline are separate forms, and decline contains no scope controls. Every POST form has one CSRF token. Spring's masked CSRF token is decoded for the SAS consent endpoint, which otherwise sits outside SAS's default CSRF protection.

Pages use no scripts or third-party resources, a `default-src 'none'` CSP with inline styles only, `frame-ancestors 'none'`, `base-uri 'none'`, no-referrer policy, and no-store cache headers. No CSP `form-action` is added because a successful authorization POST must redirect to the native client's configured callback. Cross-origin SPA token calls/CORS are not enabled automatically. Native HTTP clients plus a normal system browser for login/consent are the verified client shape.

A stale browser identity never executes an old consent POST. If its pending request still belongs to that subject/client/state and is within 10 minutes, the module reconstructs only a new GET authorization request from saved server state, including PKCE, redirect and caller state. New login and new consent are both required. It does not replay the old POST. Foreign, expired or missing pending state displays the restart message. Grants/revoke/callback-like paths are not saved as authorization resumes.

Login is limited per canonical username to 10 attempts and per direct peer IP to 120 attempts per 10 minutes, including successful logins. Counters are bounded to 10,000 keys and exist in one process; they reset on process restart. The module does not trust arbitrary forwarding headers. Configure trusted proxy handling and an edge/distributed limiter for an internet deployment or a cluster. Protocol form bodies declare a 16 KiB content-length limit and parameter values are bounded; also configure ingress/container request and form limits, especially for chunked/unknown-length bodies, before the servlet container parses parameters.

## Keys, persistent files and replacement stores

`signing-jwk` points to an external JSON private RSA JWK with a nonempty `kid` and at least 2,048 bits. `storage-key` points to an external file containing one Base64-encoded, cryptographically random 32-byte AES key. Both locations must be absolute `file:` URIs, regular files, within the size limits (16 KiB JWK / 1 KiB key), and not final-path symbolic links. Generate and provision them through the deployment's secret process, not repository defaults. The isolated sample/tests generate synthetic keys outside source control; do not reuse them for a deployed service.

Preserve both keys and the authorization directory across restarts. Restrict the service identity's directory/key ACLs, particularly on Windows; the component sets owner-only POSIX permissions where supported. Use a local filesystem with atomic replacement and durable storage. Do not copy only the encrypted file to a new deployment without its matching encryption key and signing key. Encryption key loss makes the stored authorizations unrecoverable.

The bundled file store uses AES-256-GCM with a new nonce per snapshot, authenticated format context, forced file writes and atomic replacement; it forces the directory on POSIX filesystems. A held filesystem lock prevents a second owner. Wrong keys, corruption, unsupported format, symbolic-link storage paths, lock conflicts or write failures are fail-closed. Following a write failure, the process refuses further storage operations until storage is recovered and reopened; it does not continue with an older in-memory authorization snapshot. Maximum encrypted snapshot size is 64 MiB and maximum retained authorization count is 10,000. Expired grants and expired pending requests are pruned on a subsequent successful mutation. Capacity exhaustion refuses new mutations; use a scalable store before exceeding this deployment size.

No store can protect against a privileged operator rolling an entire directory and all keys back to an older snapshot. Restoring an old snapshot can resurrect a token that had since been consumed/revoked. Disaster recovery must invalidate existing sessions/grants and require fresh authorization unless the store has an independently monotonic recovery design. File-store testing covers ordinary close/reopen and corruption/ownership rejection, not power-loss certification or a specific storage appliance's persistence guarantees.

For an alternative store, supply one `BridgeAuthorizationStore` bean. It extends standard `OAuth2AuthorizationService` and adds:

- `locked(Supplier<T>)`: serialize the entire code/refresh/revoke operation against all writers, including across nodes. Reentrant nested store operations must work.
- `findByPrincipalName`: exact stable-subject grants only.
- `findByRetiredRefreshHash`: locate the durable history used to detect refresh replay.
- `save/remove`: commit durably before returning, preserve all bridge policy/history attributes and SAS token invalidation metadata, and fail closed on errors. Reads must see authoritative committed state.

The [standard SAS storage model](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html) is reused, but a plain `JdbcOAuth2AuthorizationService` alone does not satisfy the additional atomicity and history contract. This release contains **no JDBC implementation or SQL tables**, and does not claim clustered storage readiness. A future JDBC module must include its own per-table SQL/migrations and isolated acceptance; it must not use a host's business account tables as authorization storage.

## Verification and delivery status

`AuthorizationFlowTest` drives the actual Spring Security/SAS servlet chain with synthetic accounts and external temporary keys/files. Six risk-focused scenarios cover:

1. Metadata, login, escaped consent, CSRF rejection, PKCE code exchange, current authority mapping, account disable/version rejection.
2. Refresh rotation, old access replacement, retired-refresh replay revocation, foreign-client isolation and explicit protocol revocation.
3. Expired consent GET and already-open-page POST both require fresh login and fresh consent while preserving the original PKCE and caller state.
4. Decline, foreign pending state, invalid resource/PKCE and absolute grant expiry.
5. File close/reopen restores usable authorization and later revocation; encrypted content, wrong-key and concurrent-owner rejection.
6. Own-grant management rejects foreign owners and missing CSRF, revokes immediately and preserves unrelated host session state on logout.

The module's six scenarios passed locally on 2026-09-12 on **Java 17.0.19** with Boot 3.5.14/SAS 1.5.7, following earlier targeted Java 21 runs. The targeted command is `mvn -f mcp-bridge-authorization-server/pom.xml test -Dtest=AuthorizationFlowTest`; the final baseline also generated source and Javadoc artifacts. Test usernames are isolated per scenario so unrelated scenarios do not consume each other's login quota.

This is local implementation and isolated verification. No business database, real host account integration, deployment, public artifact release, production key provisioning, live native-client acceptance or multi-instance store has been exercised by these tests. The root delivery record tracks packaging, organization namespace migration and whole-component verification separately.
