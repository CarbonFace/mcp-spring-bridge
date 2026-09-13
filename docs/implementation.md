# Implementation record

Date: 2026-09-12. Organization: Cogistra. License: MIT. Initial version: `0.1.0-SNAPSHOT`. No public repository or package release has been performed from this workspace.

## Application scenario

A Spring Boot application supplies trusted identity, business authorization and durable storage policies. Developers expose selected HTTP controllers or native Spring MCP declarations without rewriting the business service for each protocol. An employee reads authorized data, stages files and exports results; a mutation is prepared, shown, confirmed and executed using the same stored content and current permissions.

## Implementation scope

- [x] Independent Maven modules without downstream application artifacts or business databases.
- [x] Shared identity, authorization, minimal audit, file and operation APIs.
- [x] Explicit controller annotation, input/output contracts, parameter binding and actual Spring proxy invocation.
- [x] Native tools/resources/prompts/completions and explicit callbacks through the managed registry.
- [x] Write preparation, canonical execution snapshot, revision/hash confirmation, replay receipts and recovery API.
- [x] HTTP/chunk uploads, owned references, ordinary URL results and binary/Servlet exports.
- [x] Optional account-integrated OAuth server, discovery, PKCE, refresh rotation, revocation and browser pages.
- [x] Independent synthetic application and real servlet/network scenarios.
- [x] MIT license, Cogistra coordinates, consistent Java formatting, CI definition and release documentation.
- [x] Final consolidated verification and release artifact review: Java 17 build, 46 passed / 1 platform skip, source/Javadoc artifacts and license checks; see `verification.md`.
- [ ] Public GitHub/Maven release and external client/deployment acceptance.

There is no separate tool-management frontend. Configuration, host permissions and protected audit queries cover this version's management scope. Native session callbacks, WebFlux and unimplemented MVC bindings are explicit compatibility boundaries.

## Review changes

Independent review led to fixes for trusted parameter injection, unsupported async execution, dynamic mappings changing after confirmation, current method permissions on receipt access, default host HTTP protection, protocol/business serialization separation, native schema dependency convergence and sparse null semantics. Verification targets these concrete failures rather than getters or annotation presence alone.

Local stores use exclusive private directories and one process. Their receipts are not an atomic transaction with an arbitrary business database. Unknown outcomes are retained for authoritative reconciliation rather than retried. Applications with shared storage or stronger audit/transaction requirements use the documented SPIs.
