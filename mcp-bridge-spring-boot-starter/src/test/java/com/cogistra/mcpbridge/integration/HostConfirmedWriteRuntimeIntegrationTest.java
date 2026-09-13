package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.binding.BridgeContractAdapter;
import com.cogistra.mcpbridge.registry.BridgeAuthorization;
import com.cogistra.mcpbridge.registry.BridgeConfirmedWriteRuntime;
import com.cogistra.mcpbridge.registry.BridgeDiscoveryPolicy;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;

/**
 * Exercises real registration and MCP/HTTP calls using synthetic identities and temporary state.
 */
@SpringBootTest(classes = HostConfirmedWriteRuntimeIntegrationTest.Host.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class HostConfirmedWriteRuntimeIntegrationTest {
  @TempDir static Path workspace;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Facts facts;
  @Autowired Accounts accounts;
  @Autowired FixtureRuntime runtime;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("mcp.bridge.resource", () -> "https://fixture.invalid/mcp");
    p.add("mcp.bridge.authorization-servers[0]", () -> "https://fixture.invalid/oauth");
    p.add("mcp.bridge.storage", () -> workspace.resolve("state").toAbsolutePath().toString());
    p.add(
        "mcp.bridge.files.directory", () -> workspace.resolve("files").toAbsolutePath().toString());
  }

  @BeforeEach
  void reset() {
    facts.rows.clear();
    facts.revokeAfterCanonicalAuthorization.set(false);
    accounts.editor.set(true);
    runtime.intents.clear();
  }

  @Test
  void generatedLifecycleUsesTheServerSnapshotAndTheOriginalHttpContractStillWorks()
      throws Exception {
    JsonNode listed = rpc("alice", "tools/list", Map.of()).path("result").path("tools");
    Set<String> names = new HashSet<>();
    JsonNode prepareDescriptor = null;
    for (JsonNode tool : listed) {
      names.add(tool.path("name").asText());
      if (tool.path("name").asText().equals("host_create_prepare")) prepareDescriptor = tool;
    }
    assertThat(names)
        .contains(
            "host_create_prepare",
            "host_create_submit",
            "host_create_revise",
            "host_create_status",
            "host_create_cancel")
        .doesNotContain("host_create");
    assertThat(prepareDescriptor).isNotNull();
    JsonNode input = prepareDescriptor.path("inputSchema").path("properties").path("arguments");
    assertThat(input.path("properties").has("displayTitle")).isTrue();
    assertThat(input.path("properties").has("owner")).isFalse();

    JsonNode prepared = prepare("alice", "request-one", "  reviewed title  ");
    assertThat(prepared.path("review").path("title").asText()).isEqualTo("reviewed title");
    assertThat(facts.rows).isEmpty();
    // Lost preparation responses are recovered using the same request, without a second intent.
    assertThat(prepare("alice", "request-one", "  reviewed title  ")).isEqualTo(prepared);
    assertThat(success(call("alice", "host_create_status", Map.of("requestId", "request-one"))))
        .isEqualTo(prepared);

    JsonNode completed = success(call("alice", "host_create_submit", confirmation(prepared)));
    assertThat(completed.path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(completed.path("receipt").path("actor").asText()).isEqualTo("alice");
    assertThat(completed.path("receipt").path("title").asText()).isEqualTo("reviewed title");
    assertThat(facts.rows).hasSize(1);
    assertThat(success(call("alice", "host_create_submit", confirmation(prepared))))
        .isEqualTo(completed);
    assertThat(facts.rows).hasSize(1);
    assertThat(
            call(
                    "alice",
                    "bridge_operation_status",
                    Map.of("operationId", prepared.path("operationId").asText()))
                .path("isError")
                .asBoolean())
        .isTrue();

    // The same Controller still accepts its existing HTTP body, without MCP envelopes.
    MvcResult http =
        mvc.perform(
                post("/host/records")
                    .header("Authorization", "Bearer alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsBytes(Map.of("title", "ordinary HTTP", "owner", "alice"))))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(http.getResponse().getContentAsString()).path("title").asText())
        .isEqualTo("ordinary HTTP");
    assertThat(facts.rows).hasSize(2);
  }

  @Test
  void mappedInputValidationIsNotPermissionDenialAndTheSameRequestCanBeCorrected()
      throws Exception {
    // The public string schema accepts spaces; mapping trims them and the HTTP DTO rejects
    // a blank title. Reporting that as a permission failure would send the user to reconnect.
    JsonNode invalid =
        call(
            "alice",
            "host_create_prepare",
            Map.of("requestId", "correctable-request", "arguments", Map.of("displayTitle", "   ")));
    assertThat(invalid.path("isError").asBoolean()).isTrue();
    assertThat(invalid.path("structuredContent").path("errorCode").asText())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(facts.rows).isEmpty();

    JsonNode prepared = prepare("alice", "correctable-request", "corrected title");
    assertThat(
            success(call("alice", "host_create_submit", confirmation(prepared)))
                .path("receipt")
                .path("title")
                .asText())
        .isEqualTo("corrected title");
    assertThat(facts.rows).hasSize(1);

    accounts.editor.set(false);
    JsonNode denied =
        call(
            "alice",
            "host_create_prepare",
            Map.of(
                "requestId", "denied-request", "arguments", Map.of("displayTitle", "valid title")));
    assertThat(denied.path("isError").asBoolean()).isTrue();
    assertThat(denied.path("structuredContent").path("errorCode").asText()).isEqualTo("FORBIDDEN");
    assertThat(facts.rows).hasSize(1);
  }

  @Test
  void anotherEmployeeCannotReadReviseCancelOrSubmitTheOwnedReview() throws Exception {
    JsonNode alice = prepare("alice", "same-client-request", "Alice confidential draft");
    JsonNode bob = prepare("bob", "same-client-request", "Bob draft");
    assertThat(bob.path("operationId")).isNotEqualTo(alice.path("operationId"));
    String id = alice.path("operationId").asText();
    for (JsonNode denied :
        List.of(
            call("bob", "host_create_status", Map.of("operationId", id)),
            call("bob", "host_create_submit", confirmation(alice)),
            call(
                "bob",
                "host_create_revise",
                Map.of(
                    "operationId",
                    id,
                    "revision",
                    1,
                    "arguments",
                    Map.of("displayTitle", "take over"))),
            call("bob", "host_create_cancel", Map.of("operationId", id, "revision", 1)))) {
      assertThat(denied.path("isError").asBoolean()).withFailMessage(denied.toString()).isTrue();
      assertThat(denied.toString()).doesNotContain("Alice confidential draft");
    }
    assertThat(facts.rows).isEmpty();
    assertThat(success(call("alice", "host_create_status", Map.of("operationId", id))))
        .isEqualTo(alice);
    JsonNode cancelled =
        success(
            call(
                "bob",
                "host_create_cancel",
                Map.of("operationId", bob.path("operationId").asText(), "revision", 1)));
    assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED");
    assertThat(call("bob", "host_create_submit", confirmation(bob)).path("isError").asBoolean())
        .isTrue();
    assertThat(facts.rows).isEmpty();
  }

  @Test
  void invalidReplacementWithdrawsTheOldApprovalBeforeDraftValidation() throws Exception {
    JsonNode old = prepare("alice", "revise-request", "old title");
    String id = old.path("operationId").asText();
    JsonNode invalid =
        call(
            "alice",
            "host_create_revise",
            Map.of("operationId", id, "revision", 1, "arguments", Map.of("displayTitle", 123)));
    assertThat(invalid.path("isError").asBoolean()).isTrue();
    JsonNode withdrawn = success(call("alice", "host_create_status", Map.of("operationId", id)));
    assertThat(withdrawn.path("state").asText()).isEqualTo("INVALIDATED");
    assertThat(call("alice", "host_create_submit", confirmation(old)).path("isError").asBoolean())
        .isTrue();
    assertThat(facts.rows).isEmpty();

    JsonNode replacement =
        success(
            call(
                "alice",
                "host_create_revise",
                Map.of(
                    "operationId",
                    id,
                    "revision",
                    1,
                    "arguments",
                    Map.of("displayTitle", "new title"))));
    assertThat(replacement.path("revision").asLong()).isEqualTo(2);
    assertThat(replacement.path("payloadHash")).isNotEqualTo(old.path("payloadHash"));
    assertThat(call("alice", "host_create_submit", confirmation(old)).path("isError").asBoolean())
        .isTrue();
    Map<String, Object> forgedReview = new LinkedHashMap<>(confirmation(replacement));
    forgedReview.put("review", Map.of("title", "unreviewed title", "owner", "alice"));
    assertThat(call("alice", "host_create_submit", forgedReview).path("isError").asBoolean())
        .isTrue();
    assertThat(facts.rows).isEmpty();
    assertThat(
            success(call("alice", "host_create_submit", confirmation(replacement)))
                .path("receipt")
                .path("title")
                .asText())
        .isEqualTo("new title");
    assertThat(facts.rows).hasSize(1);
  }

  @Test
  void revokedScopeOrMethodPermissionCannotUseAnOldApprovalAndTheManagedProxyStillGuardsInvocation()
      throws Exception {
    JsonNode prepared = prepare("alice", "permission-request", "protected title");
    assertThat(
            call("alice-reader", "host_create_submit", confirmation(prepared))
                .path("isError")
                .asBoolean())
        .isTrue();
    accounts.editor.set(false);
    assertThat(
            call("alice", "host_create_submit", confirmation(prepared)).path("isError").asBoolean())
        .isTrue();
    mvc.perform(
            post("/host/records")
                .header("Authorization", "Bearer alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("title", "denied HTTP", "owner", "alice"))))
        .andExpect(status().isForbidden());
    assertThat(facts.rows).isEmpty();

    accounts.editor.set(true);
    // Simulate a host policy withdrawing the actor's authority after the bridge precheck.
    // Calling a raw Controller target would write; the managed proxy must reject it again.
    facts.revokeAfterCanonicalAuthorization.set(true);
    assertThat(
            call("alice", "host_create_submit", confirmation(prepared)).path("isError").asBoolean())
        .isTrue();
    assertThat(facts.rows).isEmpty();
    facts.revokeAfterCanonicalAuthorization.set(false);
    assertThat(
            success(call("alice", "host_create_submit", confirmation(prepared)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(facts.rows).hasSize(1);
  }

  private JsonNode prepare(String token, String requestId, String title) throws Exception {
    return success(
        call(
            token,
            "host_create_prepare",
            Map.of("requestId", requestId, "arguments", Map.of("displayTitle", title))));
  }

  private JsonNode success(JsonNode result) {
    assertThat(result.path("isError").asBoolean()).withFailMessage(result.toString()).isFalse();
    return result.path("structuredContent");
  }

  private Map<String, Object> confirmation(JsonNode p) {
    return Map.of(
        "operationId",
        p.path("operationId").asText(),
        "revision",
        p.path("revision").asLong(),
        "payloadHash",
        p.path("payloadHash").asText(),
        "review",
        json.convertValue(p.path("review"), Map.class));
  }

  private JsonNode call(String token, String name, Map<String, Object> args) throws Exception {
    JsonNode response = rpc(token, "tools/call", Map.of("name", name, "arguments", args));
    assertThat(response.has("error")).withFailMessage(response.toString()).isFalse();
    return response.path("result");
  }

  private JsonNode rpc(String token, String method, Map<String, Object> params) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/mcp")
                    .header("Authorization", "Bearer " + token)
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept("application/json", "text/event-stream")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))))
            .andExpect(status().isOk())
            .andReturn();
    if (result.getRequest().isAsyncStarted()) {
      result.getAsyncResult(10000);
      result = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
    }
    String body = result.getResponse().getContentAsString();
    if (body.stripLeading().startsWith("{")) return json.readTree(body);
    for (String line : body.split("\\R"))
      if (line.startsWith("data:")) {
        JsonNode value = json.readTree(line.substring(5).trim());
        if (value.has("id")) return value;
      }
    throw new AssertionError("Missing JSON-RPC response: " + body);
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
    BridgeIdentityResolver identities(Accounts accounts) {
      return accounts::resolve;
    }

    @Bean
    Facts facts() {
      return new Facts();
    }

    @Bean
    RecordController recordController(Facts facts) {
      return new RecordController(facts);
    }

    @Bean
    Contract contract(ObjectMapper json) {
      return new Contract(json);
    }

    @Bean
    FixtureRuntime runtime() {
      return new FixtureRuntime();
    }

    @Bean
    BridgeDiscoveryPolicy discovery() {
      return (name, principal) ->
          name.equals("host_create") && principal.identity().authorities().contains("ROLE_EDITOR")
              ? BridgeDiscoveryPolicy.Decision.ALLOW
              : BridgeDiscoveryPolicy.Decision.ABSTAIN;
    }

    @Bean
    BridgeAuthorization hostPolicy(Facts facts) {
      return call -> {
        if (call.arguments().containsKey("title")
            && facts.revokeAfterCanonicalAuthorization.get()) {
          SecurityContextHolder.getContext()
              .setAuthentication(
                  UsernamePasswordAuthenticationToken.authenticated(
                      call.principal().identity().subject(), null, List.of()));
        }
      };
    }

    @Bean
    @Order(100)
    SecurityFilterChain http(HttpSecurity http, Accounts accounts) throws Exception {
      return http.securityMatcher("/host/**")
          .csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .oauth2ResourceServer(
              oauth ->
                  oauth.jwt(
                      jwt ->
                          jwt.decoder(accounts::decode)
                              .jwtAuthenticationConverter(
                                  token ->
                                      (JwtAuthenticationToken)
                                          accounts
                                              .resolve(new JwtAuthenticationToken(token))
                                              .authentication())))
          .build();
    }
  }

  static class Accounts extends ServletMcpIntegrationTest.Accounts {
    final AtomicBoolean editor = new AtomicBoolean(true);

    @Override
    BridgePrincipal resolve(Authentication authentication) {
      BridgePrincipal base = super.resolve(authentication);
      Set<String> authorities = editor.get() ? Set.of("ROLE_EDITOR") : Set.of("ROLE_VIEWER");
      BridgeIdentity identity =
          new BridgeIdentity(
              base.identity().issuer(),
              base.identity().subject(),
              base.identity().clientId(),
              base.identity().scopes(),
              authorities);
      return new BridgePrincipal(
          identity,
          new JwtAuthenticationToken(
              ((JwtAuthenticationToken) authentication).getToken(),
              authorities.stream().map(SimpleGrantedAuthority::new).toList(),
              identity.subject()));
    }
  }

  public record Input(@NotBlank String title, @NotBlank String owner) {}

  public record Receipt(String id, String title, String actor) {}

  static class Facts {
    final List<Receipt> rows = new ArrayList<>();
    final AtomicBoolean revokeAfterCanonicalAuthorization = new AtomicBoolean();
  }

  @RestController
  public static class RecordController {
    private final Facts facts;

    RecordController(Facts facts) {
      this.facts = facts;
    }

    @PostMapping("/host/records")
    @PreAuthorize("hasRole('EDITOR') and #input.owner == authentication.name")
    @McpEndpoint(
        name = "host_create",
        description = "Create a synthetic owned record",
        effect = Effect.WRITE,
        confirmationRuntime = true,
        scopes = "records:write")
    public Receipt create(@Valid @RequestBody Input input, Authentication actor) {
      Receipt saved = new Receipt(UUID.randomUUID().toString(), input.title(), actor.getName());
      facts.rows.add(saved);
      return saved;
    }
  }

  static class Contract implements BridgeContractAdapter {
    private final ObjectMapper json;

    Contract(ObjectMapper json) {
      this.json = json;
    }

    public boolean supports(Method method) {
      return method.getDeclaringClass() == RecordController.class
          && method.getName().equals("create");
    }

    public JsonNode inputSchema(Method method) {
      return json.valueToTree(
          Map.of(
              "type",
              "object",
              "additionalProperties",
              false,
              "required",
              List.of("displayTitle"),
              "properties",
              Map.of("displayTitle", Map.of("type", "string", "minLength", 1, "maxLength", 80))));
    }

    public JsonNode mapInput(Method method, JsonNode input, BridgeIdentity identity) {
      return json.valueToTree(
          new Input(input.path("displayTitle").asText().strip(), identity.subject()));
    }
  }

  /** A test-only atomic memory store; it intentionally makes no database durability claim. */
  static class FixtureRuntime implements BridgeConfirmedWriteRuntime {
    final Map<String, Intent> intents = new LinkedHashMap<>();

    public String capabilityName() {
      return "host_create";
    }

    public synchronized Object prepare(
        BridgePrincipal p, Map<String, Object> args, String requestId) {
      requireEditor(p);
      for (Intent existing : intents.values())
        if (existing.owner.equals(owner(p)) && existing.requestId.equals(requestId))
          return existing.view();
      Intent intent = new Intent(owner(p), requestId, canonical(p, args));
      intents.put(intent.id, intent);
      return intent.view();
    }

    public synchronized Object revise(
        BridgePrincipal p, String id, long revision, Map<String, Object> args) {
      Intent intent = owned(p, id);
      if (intent.revision != revision || !Set.of("PREPARED", "INVALIDATED").contains(intent.state))
        throw rejected("CONFLICT");
      intent.state = "INVALIDATED"; // Persist withdrawal before replacement validation.
      Map<String, Object> replacement = canonical(p, args);
      intent.canonical = replacement;
      intent.revision++;
      intent.hash = hash(replacement, intent.revision);
      intent.state = "PREPARED";
      return intent.view();
    }

    public synchronized Object status(BridgePrincipal p, String id, String requestId) {
      if (id != null) return owned(p, id).view();
      requireEditor(p);
      return intents.values().stream()
          .filter(i -> i.owner.equals(owner(p)) && i.requestId.equals(requestId))
          .findFirst()
          .orElseThrow(() -> rejected("NOT_FOUND"))
          .view();
    }

    public synchronized Object cancel(BridgePrincipal p, String id, long revision) {
      Intent intent = owned(p, id);
      if (intent.revision != revision || !Set.of("PREPARED", "INVALIDATED").contains(intent.state))
        throw rejected("CONFLICT");
      intent.state = "CANCELLED";
      return intent.view();
    }

    public synchronized Object submit(
        BridgePrincipal p,
        String id,
        long revision,
        String hash,
        Map<String, Object> review,
        ConfirmedInvocation invoke) {
      Intent intent = owned(p, id);
      if (revision != intent.revision
          || !hash.equals(intent.hash)
          || !review.equals(intent.canonical)) throw rejected("CONFIRMATION_MISMATCH");
      if (intent.state.equals("SUCCEEDED")) return intent.view();
      if (!intent.state.equals("PREPARED")) throw rejected("CONFLICT");
      try {
        intent.receipt = invoke.invoke(intent.canonical);
        intent.state = "SUCCEEDED";
        return intent.view();
      } catch (RuntimeException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }

    private Intent owned(BridgePrincipal p, String id) {
      requireEditor(p);
      Intent value = intents.get(id);
      if (value == null || !value.owner.equals(owner(p))) throw rejected("NOT_FOUND");
      return value;
    }

    private static String owner(BridgePrincipal p) {
      return p.identity().ownerKey() + ":" + p.identity().clientId();
    }

    private static void requireEditor(BridgePrincipal p) {
      if (!p.identity().authorities().contains("ROLE_EDITOR")) throw rejected("FORBIDDEN");
    }

    private static Map<String, Object> canonical(BridgePrincipal p, Map<String, Object> args) {
      if (!args.keySet().equals(Set.of("displayTitle"))
          || !(args.get("displayTitle") instanceof String title)
          || title.isBlank()
          || title.length() > 80) throw rejected("INVALID_ARGUMENT");
      return Map.of("title", title.strip(), "owner", p.identity().subject());
    }

    private static BridgeException rejected(String code) {
      return new BridgeException(code, "Synthetic request rejected");
    }

    private static String hash(Map<String, Object> canonical, long revision) {
      try {
        byte[] input =
            (revision + ":" + canonical.get("owner") + ":" + canonical.get("title"))
                .getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    static class Intent {
      final String id = UUID.randomUUID().toString(), owner, requestId;
      long revision = 1;
      String state = "PREPARED", hash;
      Map<String, Object> canonical;
      Object receipt;

      Intent(String owner, String requestId, Map<String, Object> canonical) {
        this.owner = owner;
        this.requestId = requestId;
        this.canonical = canonical;
        this.hash = FixtureRuntime.hash(canonical, revision);
      }

      Map<String, Object> view() {
        Map<String, Object> result =
            new LinkedHashMap<>(
                Map.of(
                    "operationId",
                    id,
                    "revision",
                    revision,
                    "payloadHash",
                    hash,
                    "state",
                    state,
                    "review",
                    canonical));
        if (receipt != null) result.put("receipt", receipt);
        return result;
      }
    }
  }
}
