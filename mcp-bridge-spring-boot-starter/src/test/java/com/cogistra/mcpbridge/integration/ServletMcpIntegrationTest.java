package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.binding.BridgeContractAdapter;
import com.cogistra.mcpbridge.operation.OperationRejectedException;
import com.cogistra.mcpbridge.security.BridgeInvocationContext;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.mcp.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Real Servlet/security/SDK/managed-bean/storage chain, with synthetic host authentication and
 * facts only.
 */
@SpringBootTest(
    classes = ServletMcpIntegrationTest.Host.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ServletMcpIntegrationTest {
  @TempDir static Path workspace;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Facts facts;
  @Autowired Accounts accounts;
  @Autowired AliasMapping aliases;
  @Autowired BridgeInvocationContext identities;
  private final AtomicInteger requestNumber = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("mcp.bridge.enabled", () -> true);
    properties.add("mcp.bridge.resource", () -> "https://fixture.invalid/mcp");
    properties.add("mcp.bridge.authorization-servers[0]", () -> "https://fixture.invalid/oauth");
    properties.add(
        "mcp.bridge.storage",
        () -> workspace.resolve("operations-and-audit").toAbsolutePath().toString());
    properties.add(
        "mcp.bridge.files.directory", () -> workspace.resolve("files").toAbsolutePath().toString());
    properties.add("mcp.bridge.files.cleanup-interval", () -> "PT1H");
    properties.add("mcp.bridge.max-records", () -> 1000);
    properties.add("spring.main.banner-mode", () -> "off");
  }

  @BeforeEach
  void reset() {
    facts.reset();
    aliases.target.set(1);
    accounts.revoked.clear();
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void contextCleared() {
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    SecurityContextHolder.clearContext();
  }

  @Test
  void initializeRequiresAuthenticationAndDiscoveryUsesTheCurrentScope() throws Exception {
    mvc.perform(
            post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rpcBody("initialize", initializeParameters())))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    "WWW-Authenticate", org.hamcrest.Matchers.containsString("resource_metadata")));
    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rpcBody("initialize", initializeParameters())))
        .andExpect(status().isUnauthorized());
    JsonNode initialized = rpc("alice", "initialize", initializeParameters());
    assertThat(initialized.path("result").path("serverInfo").path("name").asText()).isNotBlank();
    assertThat(initialized.path("result").path("instructions").asText())
        .doesNotContain("bridge_get_guidance");
    Set<String> writer = toolNames(rpc("alice", "tools/list", Map.of()));
    Set<String> reader = toolNames(rpc("alice-reader", "tools/list", Map.of()));
    assertThat(writer)
        .contains(
            "fixture_read",
            "fixture_patch_prepare",
            "fixture_patch_submit",
            "native_read",
            "native_patch_prepare",
            "native_patch_submit");
    assertThat(writer).doesNotContain("fixture_patch", "native_patch");
    assertThat(reader)
        .contains("fixture_read", "native_read")
        .doesNotContain(
            "fixture_patch_prepare",
            "fixture_patch_submit",
            "native_patch_prepare",
            "native_patch_submit");
    assertThat(
            tool(
                    "alice-reader",
                    "fixture_patch_prepare",
                    Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "arguments",
                        patch("alice-1", 1, "forbidden")))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(facts.mutations.get()).isZero();
  }

  @Test
  void controllerAndNativeReadUseActualMethodSecurityAndOwnedFacts() throws Exception {
    mvc.perform(get("/fixture/alice-1").header("Authorization", "Bearer alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner").value("alice"));
    mvc.perform(get("/fixture/bob-1").header("Authorization", "Bearer alice"))
        .andExpect(status().isNotFound());
    for (String tool : List.of("fixture_read", "native_read")) {
      assertThat(data(tool("alice", tool, Map.of("id", "alice-1"))).path("owner").asText())
          .isEqualTo("alice");
      assertThat(data(tool("bob", tool, Map.of("id", "bob-1"))).path("owner").asText())
          .isEqualTo("bob");
      assertThat(tool("alice", tool, Map.of("id", "bob-1")).path("isError").asBoolean()).isTrue();
    }
    JsonNode resource = rpc("alice", "resources/read", Map.of("uri", "fixture://private"));
    assertThat(resource.path("result").path("contents").get(0).path("text").asText())
        .isEqualTo("private guide for alice");
    JsonNode prompt =
        rpc("bob", "prompts/get", Map.of("name", "fixture_prompt", "arguments", Map.of()));
    assertThat(prompt.path("result").path("messages").get(0).path("content").path("text").asText())
        .contains("bob");
    assertThat(
            rpc("alice-empty", "resources/read", Map.of("uri", "fixture://private")).has("error"))
        .isTrue();
    assertThat(
            rpc(
                    "alice-empty",
                    "prompts/get",
                    Map.of("name", "fixture_prompt", "arguments", Map.of()))
                .has("error"))
        .isTrue();
  }

  @Test
  void everyWritePreparesBeforeEffectsAndReplaysTheSameConfirmedSnapshot() throws Exception {
    JsonNode prepared = prepare("alice", "fixture_patch", patch("alice-1", 1, "after"));
    assertThat(prepared.path("state").asText()).isEqualTo("PREPARED");
    assertThat(facts.raw("alice-1").title()).isEqualTo("before alice");
    assertThat(facts.mutations.get()).isZero();
    assertThat(
            tool("bob", "fixture_patch_submit", confirmation(prepared)).path("isError").asBoolean())
        .isTrue();
    Map<String, Object> substituted = new LinkedHashMap<>(confirmation(prepared));
    substituted.put("arguments", patch("alice-1", 1, "substituted"));
    assertThat(tool("alice", "fixture_patch_submit", substituted).path("isError").asBoolean())
        .isTrue();
    JsonNode saved = data(tool("alice", "fixture_patch_submit", confirmation(prepared)));
    assertThat(saved.path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(facts.raw("alice-1").title()).isEqualTo("after");
    assertThat(facts.raw("alice-1").note()).isNull();
    assertThat(facts.mutations.get()).isEqualTo(1);
    assertThat(
            data(tool("alice", "fixture_patch_submit", confirmation(prepared)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(facts.mutations.get()).isEqualTo(1);
    JsonNode stale = prepare("alice", "fixture_patch", patch("alice-1", 1, "stale"));
    assertThat(
            data(tool("alice", "fixture_patch_submit", confirmation(stale))).path("state").asText())
        .isEqualTo("REJECTED");
    assertThat(facts.raw("alice-1").title()).isEqualTo("after");
    assertThat(facts.mutations.get()).isEqualTo(1);
  }

  @Test
  void nativeWritesCannotBypassPreparationAndRevisedPreviewsInvalidateOldConfirmation()
      throws Exception {
    assertThat(
            rpc(
                    "alice",
                    "tools/call",
                    Map.of("name", "native_patch", "arguments", patch("alice-1", 1, "bypass")))
                .has("error"))
        .isTrue();
    JsonNode nativePreview = prepare("alice", "native_patch", patch("alice-1", 1, "native"));
    assertThat(facts.mutations.get()).isZero();
    JsonNode revised =
        data(
            tool(
                "alice",
                "bridge_operation_revise",
                Map.of(
                    "operationId",
                    nativePreview.path("operationId").asText(),
                    "revision",
                    nativePreview.path("revision").asLong(),
                    "arguments",
                    patch("alice-1", 1, "revised native"))));
    assertThat(revised.path("revision").asLong())
        .isGreaterThan(nativePreview.path("revision").asLong());
    assertThat(
            tool("alice", "native_patch_submit", confirmation(nativePreview))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(facts.mutations.get()).isZero();
    assertThat(
            data(tool("alice", "native_patch_submit", confirmation(revised)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(facts.raw("alice-1").title()).isEqualTo("revised native");
    assertThat(
            data(tool("alice", "native_patch_submit", confirmation(revised)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(facts.mutations.get()).isEqualTo(1);
  }

  @Test
  void servletUploadBindsToTheOriginalMultipartMethodAndBinaryAndUrlExportsKeepTheirMeaning()
      throws Exception {
    byte[] bytes = "imported title".getBytes(StandardCharsets.UTF_8);
    var upload = new MockMultipartFile("file", "fixture.txt", "text/plain", bytes);
    mvc.perform(multipart("/mcp-bridge/files").file(upload)).andExpect(status().isUnauthorized());
    var uploaded =
        mvc.perform(
                multipart("/mcp-bridge/files").file(upload).header("Authorization", "Bearer alice"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode artifact = json.readTree(uploaded.getResponse().getContentAsByteArray());
    String fileId = artifact.path("fileId").asText();
    assertThat(fileId).isNotBlank();
    mvc.perform(get("/mcp-bridge/files/" + fileId).header("Authorization", "Bearer bob"))
        .andExpect(status().isNotFound());
    JsonNode prepared =
        prepare("alice", "fixture_import", Map.of("file", Map.of("fileId", fileId)));
    assertThat(facts.mutations.get()).isZero();
    assertThat(
            data(tool("alice", "fixture_import_submit", confirmation(prepared)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(facts.raw("alice-1").title()).isEqualTo("imported title");
    assertThat(
            tool(
                    "bob",
                    "fixture_import_prepare",
                    Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "arguments",
                        Map.of("file", Map.of("fileId", fileId))))
                .path("isError")
                .asBoolean())
        .isTrue();
    JsonNode exported = data(tool("alice", "fixture_export", Map.of("id", "alice-1")));
    assertThat(exported.path("fileId").asText()).isNotBlank();
    var download =
        mvc.perform(get(exported.path("url").asText()).header("Authorization", "Bearer alice"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(download.getResponse().getContentAsString()).isEqualTo("alice:imported title");
    mvc.perform(get(exported.path("url").asText()).header("Authorization", "Bearer bob"))
        .andExpect(status().isNotFound());
    assertThat(
            data(tool("alice", "fixture_export_url", Map.of("id", "alice-1"))).path("url").asText())
        .isEqualTo("https://fixture.invalid/owned/alice-1");
  }

  @Test
  void revocationDeniesPreparedExecutionAndTheRealInvocationContextRestoresWorkerState()
      throws Exception {
    JsonNode preview = prepare("alice", "fixture_patch", patch("alice-1", 1, "revoked"));
    accounts.revoked.add("alice");
    mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer alice")
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/json", "text/event-stream")
                .content(
                    rpcBody(
                        "tools/call",
                        Map.of(
                            "name", "fixture_patch_submit", "arguments", confirmation(preview)))))
        .andExpect(status().isUnauthorized());
    assertThat(facts.mutations.get()).isZero();
    accounts.revoked.clear();
    // First establish actual HTTP callback identities, then deterministically check the same scope
    // component on a reused worker.
    assertThat(data(tool("alice", "fixture_read", Map.of("id", "alice-1"))).path("owner").asText())
        .isEqualTo("alice");
    assertThat(data(tool("bob", "fixture_read", Map.of("id", "bob-1"))).path("owner").asText())
        .isEqualTo("bob");
    var context = facts.captured;
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      worker
          .submit(
              () -> {
                SecurityContext previous = SecurityContextHolder.createEmptyContext();
                previous.setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        "worker-sentinel",
                        "unused",
                        List.of(new SimpleGrantedAuthority("sentinel"))));
                SecurityContextHolder.setContext(previous);
                try {
                  String callbackIdentity =
                      identities.call(
                          context,
                          p -> SecurityContextHolder.getContext().getAuthentication().getName());
                  assertThat(callbackIdentity).isEqualTo("bob");
                  assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
                  assertThatThrownBy(
                          () ->
                              identities.call(
                                  context,
                                  p -> {
                                    throw new IOException("synthetic callback failure");
                                  }))
                      .isInstanceOf(IOException.class);
                  assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
                } catch (Exception unexpected) {
                  throw new RuntimeException(unexpected);
                } finally {
                  SecurityContextHolder.clearContext();
                }
              })
          .get(10, TimeUnit.SECONDS);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void auditQueryOverTheActualMcpRouteReturnsOnlyOwnMinimalMetadata() throws Exception {
    tool("alice", "fixture_read", Map.of("id", "alice-1"));
    tool("bob", "fixture_read", Map.of("id", "bob-1"));
    JsonNode first = data(tool("alice", "bridge_audit_query", Map.of("limit", 2)));
    assertThat(first.path("events").size()).isEqualTo(2);
    first
        .path("events")
        .forEach(
            event -> {
              assertThat(event.path("owner").path("subject").asText()).isEqualTo("alice");
              assertThat(event.has("arguments")).isFalse();
              assertThat(event.has("result")).isFalse();
            });
    String cursor = first.path("nextCursor").asText();
    assertThat(cursor).isNotBlank();
    assertThat(
            tool("bob", "bridge_audit_query", Map.of("limit", 2, "cursor", cursor))
                .path("isError")
                .asBoolean())
        .isTrue();
    JsonNode next = data(tool("alice", "bridge_audit_query", Map.of("limit", 2, "cursor", cursor)));
    next.path("events")
        .forEach(
            event -> assertThat(event.path("owner").path("subject").asText()).isEqualTo("alice"));
    assertThat(first.toString()).doesNotContain("before alice", "before bob", "note A", "note B");
  }

  @Test
  void changedBusinessNameResolutionRequiresAnotherPreviewAndCannotSilentlySwitchTheTarget()
      throws Exception {
    Map<String, Object> arguments = Map.of("alias", "mine", "version", 1, "title", "resolved edit");
    JsonNode prepared = prepare("alice", "fixture_mapped_patch", arguments);
    assertThat(prepared.path("preview").path("arguments").path("alias").asText()).isEqualTo("mine");
    assertThat(prepared.path("preview").path("execution").path("targetId").asText())
        .isEqualTo("alice-1");
    aliases.target.set(2);
    JsonNode rejected = data(tool("alice", "fixture_mapped_patch_submit", confirmation(prepared)));
    assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
    assertThat(rejected.path("errorCode").asText()).isEqualTo("BINDING_CHANGED");
    assertThat(facts.raw("alice-1").title()).isEqualTo("before alice");
    assertThat(facts.raw("alice-2").title()).isEqualTo("second alice");
    assertThat(facts.mutations.get()).isZero();
    JsonNode fresh = prepare("alice", "fixture_mapped_patch", arguments);
    assertThat(fresh.path("preview").path("execution").path("targetId").asText())
        .isEqualTo("alice-2");
    assertThat(fresh.path("payloadHash").asText())
        .isNotEqualTo(prepared.path("payloadHash").asText());
    assertThat(
            data(tool("alice", "fixture_mapped_patch_submit", confirmation(fresh)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(facts.raw("alice-1").title()).isEqualTo("before alice");
    assertThat(facts.raw("alice-2").title()).isEqualTo("resolved edit");
    assertThat(facts.mutations.get()).isEqualTo(1);
  }

  private Map<String, Object> initializeParameters() {
    return Map.of(
        "protocolVersion",
        "2025-06-18",
        "capabilities",
        Map.of(),
        "clientInfo",
        Map.of("name", "synthetic-http-test", "version", "1"));
  }

  private Map<String, Object> patch(String id, long version, String title) {
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("title", title);
    changes.put("note", null);
    return Map.of("id", id, "patch", Map.of("version", version, "changes", changes));
  }

  private Map<String, Object> confirmation(JsonNode preview) {
    return Map.of(
        "operationId",
        preview.path("operationId").asText(),
        "revision",
        preview.path("revision").asLong(),
        "payloadHash",
        preview.path("payloadHash").asText());
  }

  private JsonNode prepare(String token, String name, Map<String, Object> arguments)
      throws Exception {
    JsonNode response =
        tool(
            token,
            name + "_prepare",
            Map.of("requestId", UUID.randomUUID().toString(), "arguments", arguments));
    assertThat(response.path("isError").asBoolean()).withFailMessage(response.toString()).isFalse();
    return data(response);
  }

  private JsonNode tool(String token, String name, Map<String, Object> arguments) throws Exception {
    JsonNode response = rpc(token, "tools/call", Map.of("name", name, "arguments", arguments));
    assertThat(response.has("error")).withFailMessage(response.toString()).isFalse();
    return response.path("result");
  }

  private JsonNode data(JsonNode toolResult) {
    if (toolResult.hasNonNull("structuredContent")) return toolResult.path("structuredContent");
    try {
      return json.readTree(toolResult.path("content").get(0).path("text").asText());
    } catch (Exception invalid) {
      throw new AssertionError(
          "Expected a structured or JSON text tool result: " + toolResult, invalid);
    }
  }

  private Set<String> toolNames(JsonNode response) {
    Set<String> names = new HashSet<>();
    response.path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
    return names;
  }

  private byte[] rpcBody(String method, Map<String, Object> params) throws Exception {
    return json.writeValueAsBytes(
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            requestNumber.incrementAndGet(),
            "method",
            method,
            "params",
            params));
  }

  private JsonNode rpc(String token, String method, Map<String, Object> parameters)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/mcp")
                    .header("Authorization", "Bearer " + token)
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept("application/json", "text/event-stream")
                    .content(rpcBody(method, parameters)))
            .andExpect(status().isOk())
            .andReturn();
    if (result.getRequest().isAsyncStarted()) {
      result.getAsyncResult(10_000);
      result = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
    }
    String body = result.getResponse().getContentAsString();
    if (body.stripLeading().startsWith("{")) return json.readTree(body);
    for (String line : body.split("\\R"))
      if (line.startsWith("data:")) {
        JsonNode message = json.readTree(line.substring(5).trim());
        if (message.has("id")) return message;
      }
    throw new AssertionError("No JSON-RPC response in HTTP body: " + body);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableMethodSecurity(proxyTargetClass = true)
  static class Host {
    @Bean
    Accounts accounts() {
      return new Accounts();
    }

    @Bean(name = "bridgeJwtDecoder")
    JwtDecoder decoder(Accounts accounts) {
      return accounts::decode;
    }

    @Bean
    BridgeIdentityResolver identityResolver(Accounts accounts) {
      return accounts::resolve;
    }

    @Bean
    Facts facts(BridgeInvocationContext identities) {
      return new Facts(identities);
    }

    @Bean
    AliasMapping aliasMapping(ObjectMapper json) {
      return new AliasMapping(json);
    }

    @Bean
    FixtureController fixtureController(Facts facts) {
      return new FixtureController(facts);
    }

    @Bean
    NativeFixture nativeFixture(Facts facts) {
      return new NativeFixture(facts);
    }

    @Bean
    @Order(100)
    SecurityFilterChain fixtureHttpSecurity(HttpSecurity http, Accounts accounts) throws Exception {
      return http.securityMatcher("/fixture/**")
          .csrf(csrf -> csrf.disable())
          .sessionManagement(
              session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
          .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(accounts::decode)))
          .build();
    }
  }

  static class Accounts {
    final Set<String> revoked = ConcurrentHashMap.newKeySet();

    Jwt decode(String token) {
      if (!Set.of("alice", "bob", "alice-reader", "alice-empty").contains(token))
        throw new BadJwtException("Invalid synthetic token");
      String subject = token.startsWith("alice") ? "alice" : "bob";
      if (revoked.contains(subject)) throw new BadJwtException("Synthetic account revoked");
      String scopes =
          token.endsWith("empty")
              ? ""
              : token.endsWith("reader") ? "records:read" : "records:read records:write";
      return Jwt.withTokenValue(token)
          .header("alg", "RS256")
          .issuer("https://fixture.invalid/oauth")
          .subject(subject)
          .audience(List.of("https://fixture.invalid/mcp"))
          .claim("client_id", "fixture-client")
          .claim("scope", scopes)
          .issuedAt(Instant.now().minusSeconds(5))
          .expiresAt(Instant.now().plusSeconds(600))
          .build();
    }

    BridgePrincipal resolve(Authentication authentication) {
      if (!(authentication instanceof JwtAuthenticationToken token))
        throw new BridgeException("UNAUTHENTICATED", "Synthetic JWT required");
      Jwt jwt = token.getToken();
      if (revoked.contains(jwt.getSubject()))
        throw new BridgeException("UNAUTHENTICATED", "Synthetic account revoked");
      String scope = jwt.getClaimAsString("scope");
      Set<String> scopes = scope == null || scope.isBlank() ? Set.of() : Set.of(scope.split(" "));
      Set<String> authorities = new HashSet<>();
      authentication.getAuthorities().forEach(value -> authorities.add(value.getAuthority()));
      return new BridgePrincipal(
          new BridgeIdentity(
              jwt.getIssuer().toString(), jwt.getSubject(), "fixture-client", scopes, authorities),
          authentication);
    }
  }

  public record Fact(String id, String owner, long version, String title, String note) {}

  public record Patch(long version, Map<String, String> changes) {}

  public record LookupPatch(String alias, long version, String title) {}

  public record MappedPatch(String targetId, long version, String title) {}

  public record BusinessView(String recordId, String lastEditor) {}

  static class AliasMapping implements BridgeContractAdapter {
    final AtomicInteger target = new AtomicInteger(1);
    private final ObjectMapper json;

    AliasMapping(ObjectMapper json) {
      this.json = json;
    }

    public boolean supports(java.lang.reflect.Method method) {
      return method.getDeclaringClass() == FixtureController.class
          && method.getName().equals("mappedPatch");
    }

    public JsonNode mapInput(
        java.lang.reflect.Method method, JsonNode input, BridgeIdentity identity) {
      if (!"mine".equals(input.path("alias").asText()))
        throw new BridgeException("INVALID_ALIAS", "Use the synthetic mine alias");
      return json.valueToTree(
          new MappedPatch(
              identity.subject() + "-" + target.get(),
              input.path("version").asLong(),
              input.path("title").asText()));
    }
  }

  static class Facts {
    private final Map<String, Fact> rows = new HashMap<>();
    final AtomicInteger mutations = new AtomicInteger();
    final BridgeInvocationContext identities;
    volatile io.modelcontextprotocol.common.McpTransportContext captured;

    Facts(BridgeInvocationContext identities) {
      this.identities = identities;
      reset();
    }

    synchronized void reset() {
      rows.clear();
      rows.put("alice-1", new Fact("alice-1", "alice", 1, "before alice", "note A"));
      rows.put("alice-2", new Fact("alice-2", "alice", 1, "second alice", "note A2"));
      rows.put("bob-1", new Fact("bob-1", "bob", 1, "before bob", "note B"));
      mutations.set(0);
    }

    synchronized Fact raw(String id) {
      return rows.get(id);
    }

    synchronized Fact read(String id) {
      captured = identities.capture();
      String subject = SecurityContextHolder.getContext().getAuthentication().getName();
      Fact row = rows.get(id);
      if (row == null || !row.owner().equals(subject))
        throw new OperationRejectedException("NOT_FOUND", "Record not found");
      return row;
    }

    synchronized Fact patch(String id, Patch patch) {
      Fact row = read(id);
      if (row.version() != patch.version())
        throw new OperationRejectedException("VERSION_CONFLICT", "Reload the record");
      if (patch.changes() == null || !Set.of("title", "note").containsAll(patch.changes().keySet()))
        throw new OperationRejectedException("INVALID_PATCH", "Invalid fields");
      String title =
          patch.changes().containsKey("title") ? patch.changes().get("title") : row.title();
      if (title == null || title.isBlank())
        throw new OperationRejectedException("INVALID_PATCH", "Title is required");
      Fact saved =
          new Fact(
              id,
              row.owner(),
              row.version() + 1,
              title,
              patch.changes().containsKey("note") ? patch.changes().get("note") : row.note());
      rows.put(id, saved);
      mutations.incrementAndGet();
      return saved;
    }
  }

  @RestController
  @RequestMapping("/fixture")
  public static class FixtureController {
    private final Facts facts;

    FixtureController(Facts facts) {
      this.facts = facts;
    }

    @ExceptionHandler(OperationRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(OperationRejectedException failure) {
      return ResponseEntity.status(failure.code().equals("NOT_FOUND") ? 404 : 409)
          .body(Map.of("code", failure.code()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_records:read')")
    @McpEndpoint(
        name = "fixture_read",
        description = "Read own fixture",
        effect = Effect.READ,
        scopes = "records:read")
    public Fact read(@PathVariable("id") String id) {
      return facts.read(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_records:write')")
    @McpEndpoint(
        name = "fixture_patch",
        description = "Write own fixture",
        effect = Effect.WRITE,
        scopes = "records:write")
    public Fact patch(@PathVariable("id") String id, @RequestBody Patch patch) {
      return facts.patch(id, patch);
    }

    @PostMapping("/mapped")
    @PreAuthorize("hasAuthority('SCOPE_records:write')")
    @McpEndpoint(
        name = "fixture_mapped_patch",
        description = "Resolve a business alias before confirmation",
        input = LookupPatch.class,
        effect = Effect.WRITE,
        scopes = "records:write")
    public Fact mappedPatch(@RequestBody MappedPatch patch) {
      return facts.patch(
          patch.targetId(), new Patch(patch.version(), Map.of("title", patch.title())));
    }

    @GetMapping("/{id}/business-case")
    @PreAuthorize("hasAuthority('SCOPE_records:read')")
    @McpEndpoint(
        name = "fixture_business_case",
        description = "Preserve host JSON business naming",
        effect = Effect.READ,
        scopes = "records:read")
    public BusinessView businessCase(@PathVariable("id") String id) {
      Fact row = facts.read(id);
      return new BusinessView(row.id(), row.owner());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_records:write')")
    @McpEndpoint(
        name = "fixture_import",
        description = "Import fixture",
        effect = Effect.WRITE,
        scopes = "records:write")
    public Fact importFile(@RequestParam("file") MultipartFile file) throws IOException {
      String owner = SecurityContextHolder.getContext().getAuthentication().getName();
      Fact current = facts.read(owner + "-1");
      return facts.patch(
          current.id(),
          new Patch(
              current.version(),
              Map.of("title", new String(file.getBytes(), StandardCharsets.UTF_8))));
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('SCOPE_records:read')")
    @McpEndpoint(
        name = "fixture_export",
        description = "Export binary fixture",
        effect = Effect.READ,
        scopes = "records:read")
    public void export(@PathVariable("id") String id, HttpServletResponse response)
        throws IOException {
      Fact row = facts.read(id);
      response.setContentType("text/plain");
      response.setHeader("Content-Disposition", "attachment; filename=fixture.txt");
      response
          .getOutputStream()
          .write((row.owner() + ":" + row.title()).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}/url")
    @PreAuthorize("hasAuthority('SCOPE_records:read')")
    @McpEndpoint(
        name = "fixture_export_url",
        description = "Return existing URL",
        effect = Effect.READ,
        scopes = "records:read")
    public Map<String, String> exportUrl(@PathVariable("id") String id) {
      return Map.of("url", "https://fixture.invalid/owned/" + facts.read(id).id());
    }
  }

  public static class NativeFixture {
    private final Facts facts;

    NativeFixture(Facts facts) {
      this.facts = facts;
    }

    @McpTool(name = "native_read", description = "Native protected read")
    @McpPolicy(effect = Effect.READ, scopes = "records:read")
    @PreAuthorize("hasAuthority('SCOPE_records:read')")
    public Fact read(@McpArg(name = "id", required = true) String id) {
      return facts.read(id);
    }

    @McpTool(name = "native_patch", description = "Native protected write")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    @PreAuthorize("hasAuthority('SCOPE_records:write')")
    public Fact patch(
        @McpArg(name = "id", required = true) String id,
        @McpArg(name = "patch", required = true) Patch patch) {
      return facts.patch(id, patch);
    }

    @McpResource(name = "fixture_resource", uri = "fixture://private", mimeType = "text/plain")
    @McpPolicy(effect = Effect.READ, scopes = "records:read")
    @PreAuthorize("hasAuthority('SCOPE_records:read')")
    public McpSchema.ReadResourceResult resource() {
      return new McpSchema.ReadResourceResult(
          List.of(
              new McpSchema.TextResourceContents(
                  "fixture://private",
                  "text/plain",
                  "private guide for "
                      + SecurityContextHolder.getContext().getAuthentication().getName())));
    }

    @McpPrompt(name = "fixture_prompt", description = "Protected prompt")
    @McpPolicy(effect = Effect.READ, scopes = "records:read")
    @PreAuthorize("hasAuthority('SCOPE_records:read')")
    public McpSchema.GetPromptResult prompt() {
      return new McpSchema.GetPromptResult(
          "Fixture prompt",
          List.of(
              new McpSchema.PromptMessage(
                  McpSchema.Role.USER,
                  new McpSchema.TextContent(
                      "Review for "
                          + SecurityContextHolder.getContext().getAuthentication().getName()))));
    }
  }
}
