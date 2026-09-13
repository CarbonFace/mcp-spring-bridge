# Cogistra MCP Spring Bridge sample

An independent Spring Boot application with two private synthetic accounts, ordinary HTTP endpoints, controller-based MCP tools, native MCP declarations, OAuth authorization, confirmed writes, and private file import/export.

The base application disables MCP, OAuth and file storage and denies sample access. The explicit `demo` profile enables the complete local stack, binds to `127.0.0.1`, and requires externally supplied passwords and persistent keys. There are no default passwords, generated-on-startup keys, or external business database dependencies.

Follow the complete [startup, client and verification guide](../docs/sample.md). Generate local keys only by explicitly running `tools/InitializeDemoKeys.java` against a new directory. The command refuses existing paths.

From the repository root, install the libraries with `mvn -B -ntp install -DskipTests`, then run the independent HTTP/OAuth/MCP acceptance scenario:

```shell
mvn -B -ntp -f mcp-bridge-sample/pom.xml -Dtest=SampleOAuthMcpIntegrationTest test
```

The test uses generated credentials, temporary keys and files, an embedded HTTP server, the real authorization/token endpoints and the real sample beans. It does not connect to business databases or use real accounts. The sample's domain data is in memory; it is not a production persistence example.
