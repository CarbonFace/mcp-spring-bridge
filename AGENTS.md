# Component implementation rules

This is an independent reusable Spring Boot component maintained by Cogistra. Never import a downstream application's private implementation or depend on its account/role tables, business DTOs, database or workspace.

- Target baseline: Java 17, Spring Boot 3.5.14, Spring AI 1.1.8 (native MCP annotations from org.springaicommunity, version 0.9.0).
- Support explicit HTTP endpoint annotations and native Spring AI MCP declarations through the same trusted identity, authorization and audit boundary. No automatic exposure of arbitrary controllers.
- Preserve Spring managed proxies and method authorization. Reject unsafe registration; never fall back to invoking an unwrapped target.
- All business writes use per-operation preview, user confirmation, version and idempotency. Client conversation confirmation is a client obligation; the server must not claim to independently attest to a user's chat reply.
- Upload and export are in first-version scope. URL exports retain the endpoint result; private uploads and binary exports need bounded storage and access checks. Never fetch arbitrary client-supplied URLs or treat a local client path as a server path.
- Optional login/authorization module connects to host account verification and must not create a duplicate business account database. Manage through configuration and existing permissions; provide protected audit query APIs, not an extra administrative frontend.
- Tests use isolated application instances, temporary files and synthetic non-business fixtures. Never connect to a downstream application's production, legacy or development business database.
- Every capability and behavior change must be documented under docs with actual verification and pending acceptance recorded. Do not equate isolated tests with production acceptance. Database additions must include per-table SQL and migration definitions; no real database migration is authorized here.
- Do not commit, publish artifacts remotely or deploy unless asked. Local compilation/tests and local Maven installation are allowed.
