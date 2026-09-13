package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cogistra.mcpbridge.api.BridgePrincipal;
import com.cogistra.mcpbridge.guidance.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
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

/** Prevents missing instructions, unauthorized guidance reads and cross-owner bundle downloads. */
@SpringBootTest(
    classes = {ServletMcpIntegrationTest.Host.class, GuidanceMcpIntegrationTest.Guides.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuidanceMcpIntegrationTest {
  @TempDir static Path workspace;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired FixtureGuidance guidance;
  @Autowired ServletMcpIntegrationTest.Facts facts;

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
    guidance.allowed = true;
  }

  @Test
  void discoverReadAndDownloadUseTheSameVersionedInstructionsWithoutWritingBusinessData()
      throws Exception {
    JsonNode initialized =
        rpc(
            "alice",
            "initialize",
            Map.of(
                "protocolVersion",
                "2025-06-18",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "guidance-fixture", "version", "1")));
    assertThat(initialized.path("result").path("instructions").asText())
        .contains("explicit user confirmation", "bridge_get_guidance", "does not install");
    JsonNode catalog = call("alice", Map.of()).path("structuredContent");
    assertThat(catalog.path("skills").size()).isEqualTo(1);
    JsonNode result = call("alice", Map.of("skillId", "fixture-write"));
    JsonNode body = result.path("structuredContent");
    assertThat(body.path("content").asText())
        .isEqualTo("# Fixture\nRead references before confirming.\n");
    assertThat(json.readTree(result.path("content").get(0).path("text").asText())).isEqualTo(body);
    assertThat(
            call(
                    "alice",
                    Map.of(
                        "skillId",
                        "fixture-write",
                        "section",
                        "skills/fixture-write/references/rules.md"))
                .path("structuredContent")
                .path("content")
                .asText())
        .isEqualTo("Show exact review and wait.\n");
    JsonNode bundle =
        call("alice", Map.of("skillId", "fixture-write", "format", "bundle"))
            .path("structuredContent");
    String fileId = bundle.path("artifact").path("fileId").asText();
    byte[] bytes =
        mvc.perform(get("/mcp-bridge/files/" + fileId).header("Authorization", "Bearer alice"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .isEqualTo(body.path("bundleSha256").asText())
        .isEqualTo(catalog.path("skills").get(0).path("bundleSha256").asText());
    var extracted = new TreeMap<String, String>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null; )
        extracted.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertThat(extracted).containsExactlyInAnyOrderEntriesOf(FixtureGuidance.TEXT);
    mvc.perform(get("/mcp-bridge/files/" + fileId).header("Authorization", "Bearer bob"))
        .andExpect(status().isNotFound());
    assertThat(
            call("alice", Map.of("skillId", "fixture-write", "format", "bundle"))
                .path("structuredContent")
                .path("bundleSha256")
                .asText())
        .isEqualTo(bundle.path("bundleSha256").asText());
    assertThat(
            call("alice", Map.of("skillId", "fixture-write", "section", "../../application.yml"))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(
            call("alice", Map.of("section", "skills/fixture-write/SKILL.md"))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(
            call(
                    "alice",
                    Map.of(
                        "skillId",
                        "fixture-write",
                        "section",
                        "skills/fixture-write/SKILL.md",
                        "format",
                        "bundle"))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(
            call("alice", Map.of("skillId", "fixture-write", "url", "https://fixture.invalid"))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(facts.mutations.get()).isZero();
  }

  @Test
  void lossOfCurrentPermissionHidesCatalogBodyAndBundleAndDisabledDependenciesHideSkills()
      throws Exception {
    assertThat(rpc("alice", "tools/list", Map.of()).toString()).contains("bridge_get_guidance");
    for (String token : List.of("bob", "alice-reader")) {
      assertThat(rpc(token, "tools/list", Map.of()).toString()).contains("bridge_get_guidance");
      assertThat(call(token, Map.of()).path("structuredContent").path("skills").isEmpty()).isTrue();
      assertThat(call(token, Map.of("skillId", "fixture-write")).path("isError").asBoolean())
          .isTrue();
      assertThat(
              call(token, Map.of("skillId", "fixture-write", "format", "bundle"))
                  .path("isError")
                  .asBoolean())
          .isTrue();
    }
    guidance.allowed = false;
    assertThat(rpc("alice", "tools/list", Map.of()).toString()).contains("bridge_get_guidance");
    assertThat(call("alice", Map.of()).path("structuredContent").path("skills").isEmpty()).isTrue();
    for (Map<String, Object> arguments :
        List.<Map<String, Object>>of(
            Map.of("skillId", "fixture-write"),
            Map.of("skillId", "fixture-write", "format", "bundle")))
      assertThat(call("alice", arguments).path("isError").asBoolean()).isTrue();
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
    FixtureGuidance fixtureGuidance() {
      return new FixtureGuidance();
    }
  }

  static class FixtureGuidance implements BridgeGuidanceProvider {
    static final Map<String, String> TEXT =
        Map.of(
            "skills/fixture-write/SKILL.md", "# Fixture\nRead references before confirming.\n",
            "skills/fixture-write/references/rules.md", "Show exact review and wait.\n",
            ".codex-plugin/plugin.json", "{\"name\":\"fixture\"}");
    volatile boolean allowed = true;

    public List<BridgeGuidance> guidance() {
      var assets = new TreeMap<String, byte[]>();
      TEXT.forEach((path, value) -> assets.put(path, value.getBytes(StandardCharsets.UTF_8)));
      return List.of(
          new BridgeGuidance(
              "fixture-write",
              "Fixture writer",
              "Write a synthetic fixture",
              "1.0.0",
              "skills/fixture-write/SKILL.md",
              assets,
              Set.of("fixture_patch_prepare")),
          new BridgeGuidance(
              "unavailable",
              "Unavailable",
              "Dependency absent",
              "1.0.0",
              "skills/fixture-write/SKILL.md",
              assets,
              Set.of("not_published")));
    }

    public boolean available(String skillId, BridgePrincipal principal) {
      return allowed && principal.identity().subject().equals("alice");
    }
  }
}
