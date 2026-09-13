package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.api.*;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.security.RolesAllowed;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.expression.method.*;
import org.springframework.security.access.hierarchicalroles.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;

/** Revoking a method role must also deny stored receipts without re-running a business write. */
@SpringBootTest(classes = MethodGuardReceiptIntegrationTest.Host.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class MethodGuardReceiptIntegrationTest {
  @TempDir static Path workspace;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Accounts accounts;
  @Autowired Facts facts;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("mcp.bridge.resource", () -> "https://fixture.invalid/mcp");
    p.add("mcp.bridge.authorization-servers[0]", () -> "https://fixture.invalid/oauth");
    p.add("mcp.bridge.storage", () -> workspace.resolve("state").toAbsolutePath().toString());
    p.add(
        "mcp.bridge.files.directory", () -> workspace.resolve("files").toAbsolutePath().toString());
  }

  @Test
  void currentBeforeAdvisorsProtectReceiptsWithHostRolePrefixAndHierarchy() throws Exception {
    List<String> names = List.of("secured_write", "jsr_write", "pre_write");
    Map<String, JsonNode> previews = new LinkedHashMap<>();
    for (String name : names) {
      JsonNode prepared =
          call(
              name + "_prepare",
              Map.of("requestId", UUID.randomUUID().toString(), "arguments", Map.of()));
      assertThat(prepared.path("isError").asBoolean())
          .withFailMessage(prepared.toString())
          .isFalse();
      previews.put(name, prepared.path("structuredContent"));
    }
    assertThat(facts.writes).hasValue(0);
    for (String name : names) {
      JsonNode submitted = call(name + "_submit", confirmation(previews.get(name)));
      assertThat(submitted.path("structuredContent").path("state").asText())
          .withFailMessage(submitted.toString())
          .isEqualTo("SUCCEEDED");
    }
    assertThat(facts.writes).hasValue(3);
    accounts.editor.set(false);
    for (String name : names) {
      JsonNode preview = previews.get(name);
      for (JsonNode denied :
          List.of(
              call(
                  "bridge_operation_status",
                  Map.of("operationId", preview.path("operationId").asText())),
              call(name + "_submit", confirmation(preview)),
              call(
                  "bridge_operation_revise",
                  Map.of(
                      "operationId",
                      preview.path("operationId").asText(),
                      "revision",
                      preview.path("revision").asLong(),
                      "arguments",
                      Map.of())),
              call(
                  name + "_prepare",
                  Map.of("requestId", UUID.randomUUID().toString(), "arguments", Map.of())))) {
        assertThat(denied.path("isError").asBoolean()).withFailMessage(denied.toString()).isTrue();
        assertThat(denied.toString()).doesNotContain("privateReceipt");
      }
    }
    mvc.perform(post("/guarded/secured").header("Authorization", "Bearer alice"))
        .andExpect(status().isForbidden());
    assertThat(facts.writes).hasValue(3);
    String visible = rpc("tools/list", Map.of()).toString();
    names.forEach(name -> assertThat(visible).doesNotContain(name + "_prepare", name + "_submit"));
  }

  private Map<String, Object> confirmation(JsonNode p) {
    return Map.of(
        "operationId",
        p.path("operationId").asText(),
        "revision",
        p.path("revision").asLong(),
        "payloadHash",
        p.path("payloadHash").asText());
  }

  private JsonNode call(String name, Map<String, Object> args) throws Exception {
    JsonNode response = rpc("tools/call", Map.of("name", name, "arguments", args));
    assertThat(response.has("error")).withFailMessage(response.toString()).isFalse();
    return response.path("result");
  }

  private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/mcp")
                    .header("Authorization", "Bearer alice")
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
  @EnableMethodSecurity(proxyTargetClass = true, securedEnabled = true, jsr250Enabled = true)
  static class Host {
    @Bean
    static GrantedAuthorityDefaults prefix() {
      return new GrantedAuthorityDefaults("HOST_");
    }

    @Bean
    static RoleHierarchy hierarchy() {
      return RoleHierarchyImpl.fromHierarchy("HOST_ADMIN > HOST_EDITOR");
    }

    @Bean
    static MethodSecurityExpressionHandler expressions(RoleHierarchy hierarchy) {
      var handler = new DefaultMethodSecurityExpressionHandler();
      handler.setDefaultRolePrefix("HOST_");
      handler.setRoleHierarchy(hierarchy);
      return handler;
    }

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
    Facts facts() {
      return new Facts();
    }

    @Bean
    SecuredController securedController(Facts facts) {
      return new SecuredController(facts);
    }

    @Bean
    NativeMethods nativeMethods(Facts facts) {
      return new NativeMethods(facts);
    }

    @Bean
    @Order(100)
    SecurityFilterChain http(HttpSecurity http, Accounts accounts) throws Exception {
      return http.securityMatcher("/guarded/**")
          .csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(accounts::decode)))
          .build();
    }
  }

  static class Accounts extends ServletMcpIntegrationTest.Accounts {
    final AtomicBoolean editor = new AtomicBoolean(true);

    @Override
    BridgePrincipal resolve(Authentication auth) {
      BridgePrincipal base = super.resolve(auth);
      Set<String> roles = editor.get() ? Set.of("HOST_ADMIN") : Set.of("HOST_VIEWER");
      var trusted =
          new BridgeIdentity(
              base.identity().issuer(),
              base.identity().subject(),
              base.identity().clientId(),
              base.identity().scopes(),
              roles);
      return new BridgePrincipal(
          trusted,
          new JwtAuthenticationToken(
              ((JwtAuthenticationToken) auth).getToken(),
              roles.stream().map(SimpleGrantedAuthority::new).toList(),
              trusted.subject()));
    }
  }

  static class Facts {
    final AtomicInteger writes = new AtomicInteger();

    Map<String, Object> write() {
      return Map.of("privateReceipt", "synthetic secret", "version", writes.incrementAndGet());
    }
  }

  @RestController
  public static class SecuredController {
    private final Facts facts;

    SecuredController(Facts facts) {
      this.facts = facts;
    }

    @PostMapping("/guarded/secured")
    @Secured("HOST_EDITOR")
    @McpEndpoint(
        name = "secured_write",
        description = "Protected write",
        effect = Effect.WRITE,
        scopes = "records:write")
    public Map<String, Object> write() {
      return facts.write();
    }
  }

  public static class NativeMethods {
    private final Facts facts;

    NativeMethods(Facts facts) {
      this.facts = facts;
    }

    @McpTool(name = "jsr_write", description = "Protected JSR write")
    @RolesAllowed("EDITOR")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    public Map<String, Object> jsr() {
      return facts.write();
    }

    @McpTool(name = "pre_write", description = "Protected expression write")
    @PreAuthorize("hasRole('EDITOR')")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    public Map<String, Object> pre() {
      return facts.write();
    }
  }
}
