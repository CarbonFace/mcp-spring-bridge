package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;

/**
 * A host naming strategy must not rename protocol fields and break confirmation or client decoding.
 */
@SpringBootTest(
    classes = {ServletMcpIntegrationTest.Host.class, ProtocolNamingIntegrationTest.SnakeCase.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ProtocolNamingIntegrationTest {
  @TempDir static Path workspace;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("mcp.bridge.resource", () -> "https://fixture.invalid/mcp");
    properties.add("mcp.bridge.authorization-servers[0]", () -> "https://fixture.invalid/oauth");
    properties.add(
        "mcp.bridge.storage", () -> workspace.resolve("state").toAbsolutePath().toString());
    properties.add(
        "mcp.bridge.files.directory", () -> workspace.resolve("files").toAbsolutePath().toString());
    properties.add("mcp.bridge.files.cleanup-interval", () -> "PT1H");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SnakeCase {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer hostNaming() {
      return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
  }

  @Test
  void hostBusinessJsonKeepsSnakeCaseWhileProtocolAndConfirmationRemainStandard() throws Exception {
    JsonNode listed = rpc("tools/list", Map.of());
    JsonNode descriptor = null;
    for (JsonNode tool : listed.path("result").path("tools"))
      if (tool.path("name").asText().equals("fixture_patch_prepare")) descriptor = tool;
    assertThat(descriptor).isNotNull();
    assertThat(descriptor.has("inputSchema")).isTrue();
    assertThat(descriptor.has("input_schema")).isFalse();
    JsonNode business =
        rpc(
                "tools/call",
                Map.of("name", "fixture_business_case", "arguments", Map.of("id", "alice-1")))
            .path("result")
            .path("structuredContent");
    assertThat(business.path("record_id").asText()).isEqualTo("alice-1");
    assertThat(business.path("last_editor").asText()).isEqualTo("alice");
    assertThat(business.has("recordId")).isFalse();
    JsonNode prepared =
        rpc(
                "tools/call",
                Map.of(
                    "name",
                    "fixture_patch_prepare",
                    "arguments",
                    Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "arguments",
                        Map.of(
                            "id",
                            "alice-1",
                            "patch",
                            Map.of("version", 1, "changes", Map.of("title", "named"))))))
            .path("result")
            .path("structuredContent");
    assertThat(prepared.path("operationId").asText()).isNotBlank();
    assertThat(prepared.path("payloadHash").asText()).isNotBlank();
    assertThat(prepared.has("operation_id")).isFalse();
    JsonNode saved =
        rpc(
                "tools/call",
                Map.of(
                    "name",
                    "fixture_patch_submit",
                    "arguments",
                    Map.of(
                        "operationId",
                        prepared.path("operationId").asText(),
                        "revision",
                        prepared.path("revision").asLong(),
                        "payloadHash",
                        prepared.path("payloadHash").asText())))
            .path("result")
            .path("structuredContent");
    assertThat(saved.path("state").asText()).isEqualTo("SUCCEEDED");
  }

  private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
    byte[] body =
        json.writeValueAsBytes(
            Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
    MvcResult response =
        mvc.perform(
                post("/mcp")
                    .header("Authorization", "Bearer alice")
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept("application/json", "text/event-stream")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    if (response.getRequest().isAsyncStarted()) {
      response.getAsyncResult(10000);
      response = mvc.perform(asyncDispatch(response)).andExpect(status().isOk()).andReturn();
    }
    String text = response.getResponse().getContentAsString();
    if (text.stripLeading().startsWith("{")) return json.readTree(text);
    for (String line : text.split("\\R"))
      if (line.startsWith("data:")) {
        JsonNode value = json.readTree(line.substring(5).trim());
        if (value.has("id")) return value;
      }
    throw new AssertionError("Missing JSON-RPC response: " + text);
  }
}
