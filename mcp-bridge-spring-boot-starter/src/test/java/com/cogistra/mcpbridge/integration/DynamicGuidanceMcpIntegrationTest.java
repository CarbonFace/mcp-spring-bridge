package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.guidance.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;

/** Publication must not require restart, mix versions, or bypass a reader's current access. */
@SpringBootTest(
    classes = {
      ServletMcpIntegrationTest.Host.class,
      DynamicGuidanceMcpIntegrationTest.Guides.class
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DynamicGuidanceMcpIntegrationTest {
  @TempDir static Path workspace;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PublishedGuides guides;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("mcp.bridge.resource", () -> "https://fixture.invalid/mcp");
    properties.add("mcp.bridge.authorization-servers[0]", () -> "https://fixture.invalid/oauth");
    properties.add("mcp.bridge.storage", () -> workspace.resolve("state").toString());
    properties.add("mcp.bridge.files.directory", () -> workspace.resolve("files").toString());
    properties.add("mcp.bridge.files.cleanup-interval", () -> "PT1H");
  }

  @BeforeEach
  void reset() {
    guides.allowed = true;
    guides.compatible = true;
    guides.current = null;
    guides.history.clear();
  }

  @Test
  void emptyStartupCanPublishAndPinnedTextAndPrivateBundlesKeepTheOriginalVersion()
      throws Exception {
    assertThat(rpc("alice", "tools/list", Map.of()).toString())
        .contains("bridge_get_guidance", "\"version\"");
    JsonNode empty = call("alice", Map.of()).path("structuredContent");
    assertThat(empty.path("skills").size()).isEqualTo(1);
    assertThat(empty.path("skills").get(0).path("id").asText()).isEqualTo("maintenance");
    assertThat(empty.path("unavailableSkills").size()).isEqualTo(2);
    assertThat(call("alice", Map.of("skillId", "business")).toString()).contains("NOT_INITIALIZED");
    guides.publish("1");
    JsonNode before = call("alice", Map.of("skillId", "business")).path("structuredContent");
    assertThat(before.path("content").asText()).contains("version 1");
    assertThat(
            call("alice", Map.of("skillId", "orders"))
                .path("structuredContent")
                .path("bundleSha256"))
        .isEqualTo(before.path("bundleSha256"));
    guides.publish("2");
    assertThat(
            call("alice", Map.of("skillId", "business"))
                .path("structuredContent")
                .path("version")
                .asText())
        .isEqualTo("2");
    JsonNode pinned =
        call(
                "alice",
                Map.of("skillId", "business", "version", "1", "section", "references/rules.md"))
            .path("structuredContent");
    assertThat(pinned.path("content").asText()).isEqualTo("Rules for version 1");
    assertThat(pinned.path("bundleSha256")).isEqualTo(before.path("bundleSha256"));
    JsonNode exported =
        call("alice", Map.of("skillId", "orders", "version", "1", "format", "bundle"))
            .path("structuredContent");
    String fileId = exported.path("artifact").path("fileId").asText();
    byte[] archive =
        mvc.perform(get("/mcp-bridge/files/" + fileId).header("Authorization", "Bearer alice"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)))
        .isEqualTo(before.path("bundleSha256").asText());
    mvc.perform(get("/mcp-bridge/files/" + fileId).header("Authorization", "Bearer bob"))
        .andExpect(status().isNotFound());
    assertThat(call("alice", Map.of("skillId", "business", "version", "missing")).toString())
        .contains("VERSION_UNAVAILABLE");
    assertThat(
            call("alice", Map.of("skillId", "maintenance", "version", "1"))
                .path("structuredContent")
                .path("content")
                .asText())
        .isEqualTo("Protected maintenance instructions");
    assertThat(call("alice", Map.of("skillId", "maintenance", "version", "2")).toString())
        .contains("GUIDANCE_VERSION_UNAVAILABLE");
    assertThat(call("alice", Map.of("version", "1")).path("isError").asBoolean()).isTrue();
  }

  @Test
  void cachedHistoryStillChecksCurrentRoleToolAccessAndBaselineCompatibility() throws Exception {
    guides.publish("1");
    assertThat(
            call("alice", Map.of("skillId", "business", "version", "1"))
                .path("isError")
                .asBoolean())
        .isFalse();
    guides.compatible = false;
    assertThat(call("alice", Map.of("skillId", "business", "version", "1")).toString())
        .contains("BASELINE_MISMATCH");
    JsonNode incompatible = call("alice", Map.of()).path("structuredContent");
    assertThat(incompatible.path("skills").size()).isEqualTo(1);
    assertThat(incompatible.path("unavailableSkills").get(0).path("code").asText())
        .isEqualTo("BASELINE_MISMATCH");
    guides.compatible = true;
    for (String token : List.of("bob", "alice-reader")) {
      JsonNode catalog = call(token, Map.of()).path("structuredContent");
      assertThat(catalog.path("skills").size()).isEqualTo(1);
      assertThat(catalog.has("unavailableSkills")).isFalse();
      assertThat(call(token, Map.of("skillId", "business", "version", "1")).toString())
          .contains("GUIDANCE_UNAVAILABLE");
    }
    guides.allowed = false;
    assertThat(
            call("alice", Map.of("skillId", "business", "version", "1", "format", "bundle"))
                .toString())
        .contains("GUIDANCE_UNAVAILABLE");
    assertThat(call("alice", Map.of()).path("structuredContent").path("skills").size())
        .isEqualTo(1);
  }

  private JsonNode call(String token, Map<String, Object> arguments) throws Exception {
    return rpc(token, "tools/call", Map.of("name", "bridge_get_guidance", "arguments", arguments))
        .path("result");
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
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                1,
                                "method",
                                method,
                                "params",
                                parameters))))
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
    throw new AssertionError("No JSON-RPC response");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Guides {
    @Bean
    PublishedGuides publishedGuides() {
      return new PublishedGuides();
    }

    @Bean
    BridgeGuidanceProvider maintenanceGuides() {
      return () ->
          List.of(
              new BridgeGuidance(
                  "maintenance",
                  "Maintenance",
                  "Protected guide",
                  "1",
                  "SKILL.md",
                  Map.of(
                      "SKILL.md",
                      "Protected maintenance instructions".getBytes(StandardCharsets.UTF_8)),
                  Set.of()));
    }
  }

  static class PublishedGuides implements BridgeDynamicGuidanceProvider {
    final Map<String, Map<String, BridgeGuidanceSnapshot>> history = new HashMap<>();
    volatile String current;
    volatile boolean allowed = true;
    volatile boolean compatible = true;

    public List<BridgeGuidanceDescriptor> descriptors() {
      return List.of(
          new BridgeGuidanceDescriptor(
              "business", Set.of("fixture_patch_prepare"), BridgeGuidance.MAX_TOTAL_BYTES),
          new BridgeGuidanceDescriptor(
              "orders", Set.of("fixture_patch_prepare"), BridgeGuidance.MAX_TOTAL_BYTES));
    }

    void publish(String version) {
      Map<String, byte[]> assets =
          Map.of(
              "SKILL.md",
              ("Business version " + version).getBytes(StandardCharsets.UTF_8),
              "orders/SKILL.md",
              ("Order instructions version " + version).getBytes(StandardCharsets.UTF_8),
              "references/rules.md",
              ("Rules for version " + version).getBytes(StandardCharsets.UTF_8));
      var copies = new HashMap<String, BridgeGuidanceSnapshot>();
      for (var id : List.of("business", "orders"))
        copies.put(
            id,
            BridgeGuidanceSnapshot.of(
                new BridgeGuidance(
                    id,
                    id,
                    "Published instructions",
                    version,
                    id.equals("orders") ? "orders/SKILL.md" : "SKILL.md",
                    assets,
                    Set.of("fixture_patch_prepare"))));
      history.put(version, Map.copyOf(copies));
      current = version;
    }

    public BridgeGuidanceVersion resolveVersion(String skillId, String requestedVersion) {
      if (!compatible)
        throw new BridgeException("BASELINE_MISMATCH", "Review the new protected baseline");
      if (current == null)
        throw new BridgeException("NOT_INITIALIZED", "Publish the initial reviewed version");
      var release = history.get(requestedVersion == null ? current : requestedVersion);
      if (release == null)
        throw new BridgeException("VERSION_UNAVAILABLE", "Choose an existing release");
      return release.get(skillId).version();
    }

    public BridgeGuidance load(String skillId, BridgeGuidanceVersion version) {
      return history.get(version.version()).get(skillId).definition();
    }

    public boolean available(String skillId, BridgePrincipal principal) {
      return allowed && principal.identity().subject().equals("alice");
    }
  }
}
