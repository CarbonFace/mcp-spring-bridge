package com.cogistra.mcpbridge.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Actual embedded HTTP server, actual OAuth JWTs, actual sample beans and isolated files. */
@SpringBootTest(
    classes = SampleApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SampleOAuthMcpIntegrationTest {
  private static final int PORT = availablePort();
  private static final URI BASE = URI.create("http://127.0.0.1:" + PORT);
  private static final String OAUTH = "/mcp-bridge/oauth";
  private static final String RESOURCE = BASE + "/mcp";
  private static final String CALLBACK = "http://127.0.0.1:64599/callback";
  private static final String CLIENT = "sample-desktop";
  @TempDir static Path WORK;
  private static final Map<String, String> PASSWORDS = Map.of("alice", random(), "bob", random());
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient api = client();
  private final AtomicInteger rpcIds = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    keys(WORK);
    p.add("server.port", () -> PORT);
    p.add("sample.public-base-url", BASE::toString);
    p.add("sample.demo.alice-password", () -> PASSWORDS.get("alice"));
    p.add("sample.demo.bob-password", () -> PASSWORDS.get("bob"));
    p.add("mcp.bridge.resource", () -> RESOURCE);
    p.add("mcp.bridge.authorization-servers[0]", () -> BASE + OAUTH);
    p.add("mcp.bridge.authorization.issuer", () -> BASE + OAUTH);
    p.add("mcp.bridge.authorization.resource", () -> RESOURCE);
    p.add(
        "mcp.bridge.authorization.signing-jwk",
        () -> WORK.resolve("signing.jwk").toUri().toString());
    p.add(
        "mcp.bridge.authorization.storage-key",
        () -> WORK.resolve("storage.key").toUri().toString());
    p.add("mcp.bridge.storage", () -> WORK.resolve("state").toString());
    p.add("spring.main.banner-mode", () -> "off");
  }

  @Test
  void realLoginConsentTokensHttpMcpAndFilesShareTheSampleOwnershipRules() throws Exception {
    assertThat(send(api, request("/.well-known/oauth-protected-resource/mcp").GET()).statusCode())
        .isEqualTo(200);
    assertThat(
            send(
                    api,
                    request("/mcp")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")))
                .statusCode())
        .isEqualTo(401);

    // Ordinary HTTP remains a real, CSRF-protected host API using the same account facts.
    var browser = client();
    JsonNode session = read(send(browser, basic("/sample/session", "alice").GET()), 200);
    assertThat(session.path("subject").asText()).isEqualTo("alice");
    var patchBody =
        json.writeValueAsString(Map.of("version", 1, "changes", Map.of("title", "HTTP edit")));
    assertThat(
            send(
                    browser,
                    basic("/sample/records/alice-1", "alice")
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody)))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            read(
                    send(
                        browser,
                        basic("/sample/records/alice-1", "alice")
                            .header("Content-Type", "application/json")
                            .header(
                                session.path("csrfHeader").asText(),
                                session.path("csrfToken").asText())
                            .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody))),
                    200)
                .path("version")
                .asLong())
        .isEqualTo(2);
    assertThat(send(browser, basic("/sample/records/bob-1", "alice").GET()).statusCode())
        .isEqualTo(404);

    String alice = authorize("alice", List.of("sample:read", "sample:write"));
    String bob = authorize("bob", List.of("sample:read", "sample:write"));
    String reader = authorize("alice", List.of("sample:read"));
    assertThat(
            rpc(
                    alice,
                    "initialize",
                    Map.of(
                        "protocolVersion",
                        "2025-06-18",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "sample-network-test", "version", "1")))
                .path("result")
                .path("serverInfo")
                .path("name")
                .asText())
        .isEqualTo("cogistra-local-demo");
    assertThat(data(tool(alice, "sample_whoami", Map.of())).path("subject").asText())
        .isEqualTo("alice");
    assertThat(names(rpc(alice, "tools/list", Map.of())))
        .contains(
            "sample_record",
            "sample_native_record",
            "sample_patch_prepare",
            "sample_patch_submit",
            "sample_native_patch_prepare")
        .doesNotContain("sample_patch", "sample_native_patch");
    assertThat(names(rpc(reader, "tools/list", Map.of())))
        .contains("sample_record")
        .doesNotContain("sample_patch_prepare", "sample_native_patch_prepare");
    for (String toolName : List.of("sample_record", "sample_native_record")) {
      assertThat(data(tool(alice, toolName, Map.of("id", "alice-1"))).path("title").asText())
          .isEqualTo("HTTP edit");
      assertThat(data(tool(bob, toolName, Map.of("id", "bob-1"))).path("owner").asText())
          .isEqualTo("bob");
      assertThat(tool(alice, toolName, Map.of("id", "bob-1")).path("isError").asBoolean()).isTrue();
    }
    assertThat(
            tool(
                    reader,
                    "sample_patch_prepare",
                    Map.of("requestId", random(), "arguments", patch(2, "denied")))
                .path("isError")
                .asBoolean())
        .isTrue();
    JsonNode prepared = prepare(alice, "sample_patch", patch(2, "Confirmed edit"));
    assertThat(own("alice").path("title").asText()).isEqualTo("HTTP edit");
    assertThat(tool(bob, "sample_patch_submit", confirmation(prepared)).path("isError").asBoolean())
        .isTrue();
    assertThat(
            data(tool(alice, "sample_patch_submit", confirmation(prepared))).path("state").asText())
        .isEqualTo("SUCCEEDED");
    assertThat(
            data(tool(alice, "sample_patch_submit", confirmation(prepared))).path("state").asText())
        .isEqualTo("SUCCEEDED");
    assertThat(own("alice").path("version").asLong()).isEqualTo(3);
    assertThat(own("alice").path("note").isNull()).isTrue();
    JsonNode nativePrepared = prepare(alice, "sample_native_patch", patch(3, "Native edit"));
    assertThat(own("alice").path("version").asLong()).isEqualTo(3);
    assertThat(
            data(tool(alice, "sample_native_patch_submit", confirmation(nativePrepared)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    JsonNode stale = prepare(alice, "sample_patch", patch(1, "stale"));
    assertThat(data(tool(alice, "sample_patch_submit", confirmation(stale))).path("state").asText())
        .isEqualTo("REJECTED");
    assertThat(own("alice").path("version").asLong()).isEqualTo(4);

    byte[] contents =
        "[{\"title\":\"Imported via fileId\",\"note\":null}]".getBytes(StandardCharsets.UTF_8);
    JsonNode file = multipart(alice, contents);
    String fileUrl = file.path("url").asText();
    assertThat(send(api, request(fileUrl).GET()).statusCode()).isEqualTo(401);
    assertThat(send(api, bearer(fileUrl, bob).GET()).statusCode()).isEqualTo(404);
    assertThat(
            tool(
                    bob,
                    "sample_import_prepare",
                    Map.of(
                        "requestId",
                        random(),
                        "arguments",
                        Map.of("file", Map.of("fileId", file.path("fileId").asText()))))
                .path("isError")
                .asBoolean())
        .isTrue();
    JsonNode importPrepared =
        prepare(
            alice, "sample_import", Map.of("file", Map.of("fileId", file.path("fileId").asText())));
    assertThat(count("alice")).isEqualTo(1);
    assertThat(
            data(tool(alice, "sample_import_submit", confirmation(importPrepared)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(
            data(tool(alice, "sample_import_submit", confirmation(importPrepared)))
                .path("state")
                .asText())
        .isEqualTo("SUCCEEDED");
    assertThat(count("alice")).isEqualTo(2);
    assertThat(count("bob")).isEqualTo(1);

    String digest =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
    JsonNode upload =
        data(
            tool(
                alice,
                "bridge_file_begin",
                Map.of(
                    "filename",
                    "chunked.json",
                    "mediaType",
                    "application/json",
                    "totalBytes",
                    contents.length,
                    "sha256",
                    digest)));
    String uploadId = upload.path("uploadId").asText();
    assertThat(uploadId).isNotBlank();
    Map<String, Object> chunk =
        Map.of(
            "uploadId",
            uploadId,
            "offset",
            0,
            "base64",
            Base64.getEncoder().encodeToString(contents));
    assertThat(tool(alice, "bridge_file_append", chunk).path("isError").asBoolean()).isFalse();
    assertThat(tool(alice, "bridge_file_append", chunk).path("isError").asBoolean()).isFalse();
    JsonNode completed = data(tool(alice, "bridge_file_complete", Map.of("uploadId", uploadId)));
    assertThat(
            data(tool(alice, "bridge_file_complete", Map.of("uploadId", uploadId)))
                .path("fileId")
                .asText())
        .isEqualTo(completed.path("fileId").asText());
    assertThat(send(api, bearer(completed.path("url").asText(), alice).GET()).body())
        .isEqualTo(new String(contents, StandardCharsets.UTF_8));

    JsonNode exported = data(tool(alice, "sample_export_file", Map.of("id", "alice-1")));
    var download = send(api, bearer(exported.path("url").asText(), alice).GET());
    assertThat(read(download, 200).path("title").asText()).isEqualTo("Native edit");
    assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
        .startsWith("attachment;");
    assertThat(download.headers().firstValue("X-Content-Type-Options").orElse(""))
        .isEqualTo("nosniff");
    assertThat(send(api, bearer(exported.path("url").asText(), bob).GET()).statusCode())
        .isEqualTo(404);
    assertThat(send(api, request(exported.path("url").asText()).GET()).statusCode()).isEqualTo(401);
    assertThat(data(tool(alice, "sample_export_url", Map.of("id", "alice-1"))).path("url").asText())
        .isEqualTo(BASE + "/sample/records/alice-1/download");
    assertThat(send(api, bearer(fileUrl, alice).DELETE()).statusCode()).isEqualTo(204);
    assertThat(send(api, bearer(fileUrl, alice).GET()).statusCode()).isEqualTo(404);
    assertThat(
            rpc(alice, "resources/read", Map.of("uri", "sample://guide"))
                .path("result")
                .path("contents")
                .get(0)
                .path("text")
                .asText())
        .contains("Records belong to one account");
    assertThat(
            rpc(alice, "prompts/get", Map.of("name", "sample_review", "arguments", Map.of()))
                .path("result")
                .path("messages")
                .size())
        .isEqualTo(1);

    assertThat(
            form(api, OAUTH + "/revoke", List.of(pair("client_id", CLIENT), pair("token", alice)))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            send(
                    api,
                    bearer("/mcp", alice)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")))
                .statusCode())
        .isEqualTo(401);
    assertThat(data(tool(bob, "sample_whoami", Map.of())).path("subject").asText())
        .isEqualTo("bob");
  }

  private String authorize(String username, List<String> scopes) throws Exception {
    HttpClient browser = client();
    String verifier = random() + random();
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    String callerState = random();
    String start =
        OAUTH
            + "/authorize?"
            + encoded(
                List.of(
                    pair("response_type", "code"),
                    pair("client_id", CLIENT),
                    pair("redirect_uri", CALLBACK),
                    pair("scope", String.join(" ", scopes)),
                    pair("state", callerState),
                    pair("resource", RESOURCE),
                    pair("code_challenge", challenge),
                    pair("code_challenge_method", "S256")));
    assertThat(send(browser, request(start).GET()).statusCode()).isEqualTo(302);
    var login = send(browser, request(OAUTH + "/login").GET());
    assertThat(login.statusCode()).isEqualTo(200);
    var logged =
        form(
            browser,
            OAUTH + "/login",
            List.of(
                pair("username", username),
                pair("password", PASSWORDS.get(username)),
                pair("_csrf", csrf(login.body()))));
    String resumed = redirect(logged);
    assertThat(resumed).doesNotContain("?error");
    String consentLocation = redirect(send(browser, request(resumed).GET()));
    var consent = send(browser, request(consentLocation).GET());
    assertThat(consent.statusCode()).isEqualTo(200);
    List<Map.Entry<String, String>> approval =
        new ArrayList<>(
            List.of(
                pair("client_id", CLIENT),
                pair("state", query(consentLocation).get("state")),
                pair("_csrf", csrf(consent.body()))));
    scopes.forEach(scope -> approval.add(pair("scope", scope)));
    Map<String, String> callback = query(redirect(form(browser, OAUTH + "/authorize", approval)));
    assertThat(callback.get("state")).isEqualTo(callerState);
    String code = callback.get("code");
    assertThat(code).isNotBlank();
    var exchange =
        List.of(
            pair("grant_type", "authorization_code"),
            pair("client_id", CLIENT),
            pair("code", code),
            pair("redirect_uri", CALLBACK),
            pair("code_verifier", verifier),
            pair("resource", RESOURCE));
    JsonNode tokens = read(form(api, OAUTH + "/token", exchange), 200);
    assertThat(tokens.path("token_type").asText()).isEqualToIgnoringCase("Bearer");
    assertThat(tokens.path("refresh_token").asText()).isNotBlank();
    return tokens.path("access_token").asText();
  }

  private JsonNode multipart(String token, byte[] bytes) throws Exception {
    String boundary = "sample-" + UUID.randomUUID();
    String body =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.json\"\r\nContent-Type: application/json\r\n\r\n"
            + new String(bytes, StandardCharsets.UTF_8)
            + "\r\n--"
            + boundary
            + "--\r\n";
    return read(
        send(
            api,
            bearer("/mcp-bridge/files", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))),
        200);
  }

  private JsonNode own(String account) throws Exception {
    return read(send(api, basic("/sample/records/" + account + "-1", account).GET()), 200);
  }

  private int count(String account) throws Exception {
    return read(send(api, basic("/sample/records", account).GET()), 200).size();
  }

  private Map<String, Object> patch(long version, String title) {
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("title", title);
    changes.put("note", null);
    return Map.of("id", "alice-1", "patch", Map.of("version", version, "changes", changes));
  }

  private Map<String, Object> confirmation(JsonNode view) {
    return Map.of(
        "operationId",
        view.path("operationId").asText(),
        "revision",
        view.path("revision").asLong(),
        "payloadHash",
        view.path("payloadHash").asText());
  }

  private JsonNode prepare(String token, String name, Map<String, Object> arguments)
      throws Exception {
    JsonNode result =
        tool(token, name + "_prepare", Map.of("requestId", random(), "arguments", arguments));
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    return data(result);
  }

  private JsonNode tool(String token, String name, Map<String, Object> arguments) throws Exception {
    JsonNode result = rpc(token, "tools/call", Map.of("name", name, "arguments", arguments));
    assertThat(result.has("error")).as(result.toString()).isFalse();
    return result.path("result");
  }

  private JsonNode data(JsonNode result) throws Exception {
    return result.hasNonNull("structuredContent")
        ? result.path("structuredContent")
        : json.readTree(result.path("content").get(0).path("text").asText());
  }

  private Set<String> names(JsonNode response) {
    Set<String> names = new java.util.HashSet<>();
    response.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
    return names;
  }

  private JsonNode rpc(String token, String method, Map<String, Object> arguments)
      throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                rpcIds.incrementAndGet(),
                "method",
                method,
                "params",
                arguments));
    var response =
        send(
            api,
            bearer("/mcp", token)
                .header("MCP-Protocol-Version", "2025-06-18")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertThat(response.statusCode()).as("MCP " + method).isEqualTo(200);
    if (response.body().stripLeading().startsWith("{")) return json.readTree(response.body());
    for (String line : response.body().split("\\R"))
      if (line.startsWith("data:")) {
        JsonNode message = json.readTree(line.substring(5).trim());
        if (message.has("id")) return message;
      }
    throw new AssertionError("Missing JSON-RPC response");
  }

  private HttpRequest.Builder basic(String path, String user) {
    return request(path)
        .header(
            "Authorization",
            "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        (user + ":" + PASSWORDS.get(user)).getBytes(StandardCharsets.UTF_8)));
  }

  private HttpRequest.Builder bearer(String path, String token) {
    return request(path).header("Authorization", "Bearer " + token);
  }

  private HttpRequest.Builder request(String path) {
    URI uri = BASE.resolve(path);
    if (!uri.getScheme().equals("http")
        || !uri.getHost().equals("127.0.0.1")
        || uri.getPort() != PORT) throw new AssertionError("Unexpected nonlocal request target");
    return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
  }

  private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
      throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> form(
      HttpClient client, String path, List<Map.Entry<String, String>> fields) throws Exception {
    return send(
        client,
        request(path)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encoded(fields))));
  }

  private JsonNode read(HttpResponse<String> response, int status) throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    return json.readTree(response.body());
  }

  private static String redirect(HttpResponse<String> response) {
    assertThat(response.statusCode()).isBetween(300, 399);
    return response.headers().firstValue("Location").orElseThrow();
  }

  private static String csrf(String html) {
    var matcher = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private static Map.Entry<String, String> pair(String name, String value) {
    return Map.entry(name, value);
  }

  private static String encoded(List<Map.Entry<String, String>> fields) {
    return fields.stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static Map<String, String> query(String uri) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : URI.create(uri).getRawQuery().split("&")) {
      String[] values = pair.split("=", 2);
      result.put(
          URLDecoder.decode(values[0], StandardCharsets.UTF_8),
          URLDecoder.decode(values.length > 1 ? values[1] : "", StandardCharsets.UTF_8));
    }
    return result;
  }

  private static HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  private static String random() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static int availablePort() {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (Exception failed) {
      throw new ExceptionInInitializerError(failed);
    }
  }

  private static void keys(Path directory) {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      Files.writeString(
          directory.resolve("signing.jwk"),
          new RSAKey.Builder((RSAPublicKey) pair.getPublic())
              .privateKey((RSAPrivateKey) pair.getPrivate())
              .keyID("isolated-network-test")
              .build()
              .toJSONString());
      byte[] key = new byte[32];
      new SecureRandom().nextBytes(key);
      Files.writeString(directory.resolve("storage.key"), Base64.getEncoder().encodeToString(key));
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }
}
