package com.cogistra.mcpbridge.authorization;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cogistra.mcpbridge.api.BridgeIdentityResolver;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;

/**
 * Real Spring Security/SAS servlet chain, temporary encrypted store and synthetic host accounts.
 */
@SpringBootTest(
    classes = AuthorizationFlowTest.App.class,
    properties = {
      "mcp.bridge.authorization.enabled=true",
      "mcp.bridge.authorization.development-loopback=true",
      "mcp.bridge.authorization.browser-session-ttl=2s",
      "mcp.bridge.authorization.issuer=http://127.0.0.1:9000/mcp-bridge/oauth",
      "mcp.bridge.authorization.resource=http://127.0.0.1:9000/mcp",
      "mcp.bridge.authorization.clients[0].id=desktop",
      "mcp.bridge.authorization.clients[0].name=Desktop <script>alert(1)</script>",
      "mcp.bridge.authorization.clients[0].redirect-uris[0]=http://127.0.0.1:64599/callback",
      "mcp.bridge.authorization.clients[0].scopes[0]=orders:read",
      "mcp.bridge.authorization.clients[0].scopes[1]=orders:write",
      "mcp.bridge.authorization.clients[1].id=other",
      "mcp.bridge.authorization.clients[1].name=Other client",
      "mcp.bridge.authorization.clients[1].redirect-uris[0]=http://127.0.0.1:64599/callback",
      "mcp.bridge.authorization.clients[1].scopes[0]=orders:read"
    })
@AutoConfigureMockMvc
class AuthorizationFlowTest {
  static final String BASE = "/mcp-bridge/oauth", RESOURCE = "http://127.0.0.1:9000/mcp";
  static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNO";
  static final Path TEMP = createKeys();
  static final Map<String, HostAccount> ACCOUNTS = new ConcurrentHashMap<>();
  static String accountSuffix;
  @Autowired MockMvc mvc;

  @Autowired
  @Qualifier("bridgeJwtDecoder")
  JwtDecoder decoder;

  @Autowired BridgeIdentityResolver resolver;
  @Autowired BridgeAuthorizationService service;
  @Autowired BridgeAuthorizationProperties properties;

  @Autowired
  @Qualifier("bridgeAuthorizationClients")
  RegisteredClientRepository clients;

  @Autowired
  @Qualifier("bridgeAuthorizationClock")
  TestClock clock;

  final ObjectMapper json = new ObjectMapper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "mcp.bridge.authorization.storage-directory", () -> TEMP.resolve("live").toString());
    registry.add(
        "mcp.bridge.authorization.storage-key",
        () -> TEMP.resolve("storage.key").toUri().toString());
    registry.add(
        "mcp.bridge.authorization.signing-jwk",
        () -> TEMP.resolve("signing.jwk").toUri().toString());
  }

  @BeforeEach
  void reset() {
    accountSuffix = UUID.randomUUID().toString();
    clock.now = Instant.now();
    ACCOUNTS.put("user-1", account("v1", true));
    ACCOUNTS.put(
        "user-2",
        new HostAccount("user-2", "bob-" + accountSuffix, "Bob", true, "v1", Set.of("read")));
  }

  @Test
  void authorizationUsesHostIdentityExplicitConsentPkceAndCurrentPermissions() throws Exception {
    mvc.perform(get("/.well-known/oauth-authorization-server/mcp-bridge/oauth"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.authorization_endpoint")
                .value("http://127.0.0.1:9000/mcp-bridge/oauth/authorize"))
        .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
    mvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
        .andExpect(jsonPath("$.resource").value(RESOURCE));
    var flow = consent("alice", "caller & state=✓");
    assertThat(flow.html).contains("Desktop &lt;script&gt;").doesNotContain("<script>alert");
    mvc.perform(
            post(BASE + "/authorize")
                .session(flow.session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", "desktop")
                .param("state", flow.state)
                .param("scope", "orders:read"))
        .andExpect(status().isForbidden());
    String code = approve(flow, "orders:read");
    mvc.perform(
            post(BASE + "/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", "desktop")
                .param("code", code)
                .param("redirect_uri", "http://127.0.0.1:64599/callback")
                .param("code_verifier", "incorrect-verifier")
                .param("resource", RESOURCE))
        .andExpect(status().is4xxClientError());
    var tokens = exchange(code);
    Jwt jwt = decoder.decode(tokens.get("access_token").asText());
    assertThat(jwt.getAudience()).containsExactly(RESOURCE);
    assertThat(jwt.getSubject()).isEqualTo("user-1");
    assertThat(
            resolver.resolve(new JwtAuthenticationToken(jwt, List.of())).identity().authorities())
        .containsExactlyInAnyOrder("read", "write");
    assertThat(jwt.getClaimAsStringList("scope")).containsExactly("orders:read");
    mvc.perform(get(BASE + "/grants").session(flow.session))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    ACCOUNTS.put("user-1", account("v1", false));
    assertThatThrownBy(() -> decoder.decode(tokens.get("access_token").asText()))
        .isInstanceOf(JwtException.class);
    ACCOUNTS.put("user-1", account("v2", true));
    assertThatThrownBy(() -> decoder.decode(tokens.get("access_token").asText()))
        .isInstanceOf(JwtException.class);
    refresh(tokens.get("refresh_token").asText(), "desktop")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  @Test
  void rotatingRefreshReplayRevokesTheFamilyAndRevocationIsImmediate() throws Exception {
    var first = exchange(approve(consent("alice", "rotation"), "orders:read"));
    var rotated =
        read(
            refresh(first.get("refresh_token").asText(), "desktop")
                .andExpect(status().isOk())
                .andReturn());
    assertThat(rotated.get("refresh_token").asText())
        .isNotEqualTo(first.get("refresh_token").asText());
    assertThatThrownBy(() -> decoder.decode(first.get("access_token").asText()))
        .isInstanceOf(JwtException.class);
    decoder.decode(rotated.get("access_token").asText());
    refresh(first.get("refresh_token").asText(), "other").andExpect(status().isBadRequest());
    decoder.decode(
        rotated
            .get("access_token")
            .asText()); // Foreign client cannot revoke another client's family.
    refresh(first.get("refresh_token").asText(), "desktop").andExpect(status().isBadRequest());
    assertThatThrownBy(() -> decoder.decode(rotated.get("access_token").asText()))
        .isInstanceOf(JwtException.class);
    var second = exchange(approve(consent("alice", "revocation"), "orders:read"));
    mvc.perform(
            post(BASE + "/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", "desktop")
                .param("token", second.get("refresh_token").asText()))
        .andExpect(status().isOk());
    assertThatThrownBy(() -> decoder.decode(second.get("access_token").asText()))
        .isInstanceOf(JwtException.class);
    refresh(second.get("refresh_token").asText(), "desktop").andExpect(status().isBadRequest());
  }

  @Test
  void expiredConsentGetAndPostRequireNewLoginAndNewConsentWithoutLosingCallerState()
      throws Exception {
    for (boolean alreadyOpened : List.of(false, true)) {
      var old = consent("alice", "recover + & / = ✓ " + alreadyOpened);
      clock.now = clock.now.plus(properties.getBrowserSessionTtl()).plusSeconds(1);
      var response =
          alreadyOpened
              ? mvc.perform(
                      post(BASE + "/authorize")
                          .session(old.session)
                          .param("client_id", "desktop")
                          .param("state", old.state)
                          .param("scope", "orders:read")
                          .param("_csrf", csrf(old.html)))
                  .andExpect(status().is3xxRedirection())
                  .andReturn()
              : mvc.perform(
                      get(BASE + "/consent")
                          .session(old.session)
                          .param("client_id", "desktop")
                          .param("state", old.state))
                  .andExpect(status().is3xxRedirection())
                  .andReturn();
      assertThat(response.getResponse().getRedirectedUrl()).endsWith("/login?expired");
      login(old.session, "alice");
      var authorize =
          mvc.perform(get(URI.create(lastLoginRedirect)).session(old.session))
              .andExpect(status().is3xxRedirection())
              .andReturn();
      String page = authorize.getResponse().getRedirectedUrl();
      assertThat(page).contains("/consent?");
      var fresh = readConsent(old.session, page, old.callerState);
      assertThat(fresh.state).isNotEqualTo(old.state);
      exchange(approve(fresh, "orders:read"));
      clock.now = Instant.now();
    }
  }

  @Test
  void denialForeignStateResourcePkceAndAbsoluteExpiryFailClosed() throws Exception {
    var flow = consent("alice", "denied");
    mvc.perform(
            post(BASE + "/authorize")
                .session(flow.session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", "desktop")
                .param("state", flow.state)
                .param("_csrf", csrf(flow.html)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("http://127.0.0.1:64599/callback?error=access_denied*"));
    var foreign = consent("bob", "foreign");
    var own = consent("alice", "own");
    clock.now = clock.now.plus(properties.getBrowserSessionTtl()).plusSeconds(1);
    mvc.perform(
            get(BASE + "/consent")
                .session(own.session)
                .param("client_id", "desktop")
                .param("state", foreign.state))
        .andExpect(redirectedUrl(BASE + "/login?expired&restart"));
    clock.now = Instant.now();
    var invalid =
        mvc.perform(authorizeRequest("bad").queryParam("resource", "https://foreign.example/mcp"))
            .andReturn();
    assertThat(
            Objects.toString(invalid.getResponse().getRedirectedUrl(), "")
                + Objects.toString(invalid.getResponse().getErrorMessage(), "")
                + invalid.getResponse().getContentAsString())
        .contains("invalid_target");
    var missingPkce =
        mvc.perform(
                get(BASE + "/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", "desktop")
                    .queryParam("redirect_uri", "http://127.0.0.1:64599/callback")
                    .queryParam("scope", "orders:read")
                    .queryParam("resource", RESOURCE))
            .andReturn();
    assertThat(
            Objects.toString(
                missingPkce.getResponse().getRedirectedUrl(),
                missingPkce.getResponse().getContentAsString()))
        .contains("invalid_request");
    var valid = exchange(approve(consent("alice", "absolute"), "orders:read"));
    clock.now = clock.now.plus(properties.getAuthorizationTtl()).plusSeconds(1);
    assertThatThrownBy(() -> decoder.decode(valid.get("access_token").asText()))
        .isInstanceOf(JwtException.class);
    refresh(valid.get("refresh_token").asText(), "desktop").andExpect(status().isBadRequest());
  }

  @Test
  void durableStoreSurvivesReopenAndRejectsWrongKeysAndConcurrentOwners() throws Exception {
    var tokens = exchange(approve(consent("alice", "persist"), "orders:read"));
    var authorization =
        service.findByToken(
            tokens.get("access_token").asText(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    Path directory = Files.createTempDirectory(TEMP, "restart-");
    byte[] key = Base64.getDecoder().decode(Files.readString(TEMP.resolve("storage.key")));
    try (var store = new FileAuthorizationStore(directory, key, clients)) {
      store.save(authorization);
      assertThatThrownBy(() -> new FileAuthorizationStore(directory, key, clients))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(
            Files.readString(
                directory.resolve("authorizations.enc"),
                java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(tokens.get("refresh_token").asText());
    try (var reopened = new FileAuthorizationStore(directory, key, clients)) {
      var policy =
          new BridgeAuthorizationService(
              reopened, s -> Optional.ofNullable(ACCOUNTS.get(s)), properties, clock);
      assertThat(policy.currentAccountForAccess(tokens.get("access_token").asText()).subject())
          .isEqualTo("user-1");
      policy.revokeForSubject("user-1", authorization.getId());
    }
    try (var reopened = new FileAuthorizationStore(directory, key, clients)) {
      assertThat(
              (Boolean)
                  reopened
                      .findById(authorization.getId())
                      .getAttribute(BridgeAuthorizationService.REVOKED))
          .isTrue();
    }
    assertThatThrownBy(() -> new FileAuthorizationStore(directory, new byte[32], clients))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ownGrantManagementRejectsForeignOwnersAndCsrfAndPreservesHostSession() throws Exception {
    var own = consent("alice", "own-management");
    var tokens = exchange(approve(own, "orders:read"));
    String id =
        service
            .findByToken(
                tokens.get("access_token").asText(),
                org.springframework.security.oauth2.server.authorization.OAuth2TokenType
                    .ACCESS_TOKEN)
            .getId();
    var foreign = consent("bob", "foreign-management");
    String foreignHtml =
        mvc.perform(get(BASE + "/grants").session(foreign.session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(foreignHtml).doesNotContain(id);
    mvc.perform(
            post(BASE + "/grants/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .session(foreign.session)
                .param("id", id)
                .param("_csrf", csrf(foreignHtml)))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(BASE + "/grants/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .session(own.session)
                .param("id", id))
        .andExpect(status().isForbidden());
    decoder.decode(tokens.get("access_token").asText());
    String ownHtml =
        mvc.perform(get(BASE + "/grants").session(own.session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post(BASE + "/grants/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .session(own.session)
                .param("id", id)
                .param("_csrf", csrf(ownHtml)))
        .andExpect(status().isSeeOther());
    assertThatThrownBy(() -> decoder.decode(tokens.get("access_token").asText()))
        .isInstanceOf(JwtException.class);
    own.session.setAttribute("HOST_STATE", "host-login");
    ownHtml =
        mvc.perform(get(BASE + "/grants").session(own.session))
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(post(BASE + "/logout").session(own.session).param("_csrf", csrf(ownHtml)))
        .andExpect(status().is3xxRedirection());
    assertThat(own.session.getAttribute("HOST_STATE")).isEqualTo("host-login");
    assertThat(own.session.getAttribute(BridgeBrowserSupport.CONTEXT)).isNull();
  }

  private String lastLoginRedirect;

  private Flow consent(String username, String state) throws Exception {
    var initial =
        mvc.perform(authorizeRequest(state)).andExpect(status().is3xxRedirection()).andReturn();
    var session = (MockHttpSession) initial.getRequest().getSession(false);
    login(session, username);
    var response =
        mvc.perform(get(URI.create(lastLoginRedirect)).session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    return readConsent(session, response.getResponse().getRedirectedUrl(), state);
  }

  private void login(MockHttpSession session, String username) throws Exception {
    String html =
        mvc.perform(get(BASE + "/login").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    lastLoginRedirect =
        mvc.perform(
                post(BASE + "/login")
                    .session(session)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("username", " " + username + "-" + accountSuffix + " ")
                    .param("password", "synthetic-password")
                    .param("_csrf", csrf(html)))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    assertThat(lastLoginRedirect).doesNotContain("?error");
  }

  private Flow readConsent(MockHttpSession session, String url, String callerState)
      throws Exception {
    var result =
        mvc.perform(get(URI.create(url)).session(session))
            .andExpect(status().isOk())
            .andExpect(header().exists("Content-Security-Policy"))
            .andReturn();
    return new Flow(
        session, query(url).get("state"), callerState, result.getResponse().getContentAsString());
  }

  private String approve(Flow flow, String scope) throws Exception {
    var result =
        mvc.perform(
                post(BASE + "/authorize")
                    .session(flow.session)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("client_id", "desktop")
                    .param("state", flow.state)
                    .param("scope", scope)
                    .param("_csrf", csrf(flow.html)))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    var parameters = query(result.getResponse().getRedirectedUrl());
    assertThat(parameters.get("state")).isEqualTo(flow.callerState);
    assertThat(parameters).containsKey("code");
    return parameters.get("code");
  }

  private JsonNode exchange(String code) throws Exception {
    return read(
        mvc.perform(
                post(BASE + "/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "authorization_code")
                    .param("client_id", "desktop")
                    .param("code", code)
                    .param("redirect_uri", "http://127.0.0.1:64599/callback")
                    .param("code_verifier", VERIFIER)
                    .param("resource", RESOURCE))
            .andExpect(status().isOk())
            .andReturn());
  }

  private ResultActions refresh(String token, String client) throws Exception {
    return mvc.perform(
        post(BASE + "/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "refresh_token")
            .param("client_id", client)
            .param("refresh_token", token)
            .param("resource", RESOURCE));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      authorizeRequest(String state) throws Exception {
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    return get(BASE + "/authorize")
        .queryParam("response_type", "code")
        .queryParam("client_id", "desktop")
        .queryParam("redirect_uri", "http://127.0.0.1:64599/callback")
        .queryParam("scope", "orders:read orders:write")
        .queryParam("state", state)
        .queryParam("resource", RESOURCE)
        .queryParam("code_challenge", challenge)
        .queryParam("code_challenge_method", "S256");
  }

  private JsonNode read(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private static String csrf(String html) {
    var m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
    assertThat(m.find()).isTrue();
    return m.group(1);
  }

  private static Map<String, String> query(String uri) {
    var result = new HashMap<String, String>();
    for (String pair : URI.create(uri).getRawQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
    }
    return result;
  }

  private static HostAccount account(String version, boolean enabled) {
    return new HostAccount(
        "user-1",
        "alice-" + accountSuffix,
        "Alice <script>alert(1)</script>",
        enabled,
        version,
        Set.of("read", "write"));
  }

  private static Path createKeys() {
    try {
      Path directory = Files.createTempDirectory("mcp-bridge-oauth-test-");
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      var key =
          new RSAKey.Builder((RSAPublicKey) pair.getPublic())
              .privateKey((RSAPrivateKey) pair.getPrivate())
              .keyID("synthetic-test-key")
              .build();
      Files.writeString(directory.resolve("signing.jwk"), key.toJSONString());
      byte[] storage = new byte[32];
      new SecureRandom().nextBytes(storage);
      Files.writeString(
          directory.resolve("storage.key"), Base64.getEncoder().encodeToString(storage));
      return directory;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  record Flow(MockHttpSession session, String state, String callerState, String html) {}

  static final class TestClock extends Clock {
    Instant now = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean
    HostAccountDirectory directory() {
      return subject -> Optional.ofNullable(ACCOUNTS.get(subject));
    }

    @Bean
    HostPasswordVerifier verifier() {
      return (name, password) ->
          Arrays.equals(password, "synthetic-password".toCharArray())
              ? ACCOUNTS.values().stream().filter(a -> a.username().equals(name)).findFirst()
              : Optional.empty();
    }

    @Bean("bridgeAuthorizationClock")
    TestClock clock() {
      return new TestClock();
    }
  }
}
