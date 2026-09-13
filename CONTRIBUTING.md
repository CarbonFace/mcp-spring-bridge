# Contributing

MCP Spring Bridge is maintained under the Cogistra organization and licensed under MIT.

Use Java 17 or 21 and Maven 3.8.8 or later. Run `mvn verify` from the repository root. Tests use synthetic accounts, embedded servlet applications and temporary local storage. They must never depend on a company's database, private source repository, production credentials or deployed service.

Keep the controller adapter, native annotation adapter, shared invocation policy, operation receipts, file storage and optional authorization server separate. Every exposed entry must keep the same verified identity and host business permissions. Never unwrap a Spring proxy to evade an interceptor or register a second unguarded MCP server.

For a behavior change, update the relevant document and `CHANGELOG.md`, describe the real user action it supports, and add focused verification for the failure it prevents. Preserve existing HTTP behavior; unsupported transport bindings must produce explicit configuration errors rather than silently losing parameters.

Contributions are provided under the repository's MIT license. Do not include secrets, customer information, proprietary implementations or code whose license is incompatible with its intended distribution.
