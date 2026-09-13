package com.cogistra.mcpbridge.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cogistra.mcpbridge.api.BridgeIdentityResolver;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

/**
 * Installing a narrow MCP security chain must not expose an otherwise default-secured host route.
 */
@SpringBootTest(classes = StarterDefaultSecurityIntegrationTest.DefaultHost.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class StarterDefaultSecurityIntegrationTest {
  @TempDir static Path workspace;
  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("mcp.bridge.enabled", () -> true);
    properties.add("mcp.bridge.resource", () -> "https://fixture.invalid/mcp");
    properties.add("mcp.bridge.authorization-servers[0]", () -> "https://fixture.invalid/oauth");
    properties.add(
        "mcp.bridge.storage", () -> workspace.resolve("state").toAbsolutePath().toString());
    properties.add(
        "mcp.bridge.files.directory", () -> workspace.resolve("files").toAbsolutePath().toString());
    properties.add("mcp.bridge.files.cleanup-interval", () -> "PT1H");
  }

  @Test
  void defaultProtectedHostRouteRemainsProtectedAfterStarterIsInstalled() throws Exception {
    mvc.perform(get("/private").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/.well-known/oauth-protected-resource").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resource").value("https://fixture.invalid/mcp"));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableMethodSecurity
  static class DefaultHost {
    @Bean
    ServletMcpIntegrationTest.Accounts accounts() {
      return new ServletMcpIntegrationTest.Accounts();
    }

    @Bean(name = "bridgeJwtDecoder")
    JwtDecoder decoder(ServletMcpIntegrationTest.Accounts accounts) {
      return accounts::decode;
    }

    @Bean
    BridgeIdentityResolver identityResolver(ServletMcpIntegrationTest.Accounts accounts) {
      return accounts::resolve;
    }

    @Bean
    PrivateController privateController() {
      return new PrivateController();
    }
  }

  @RestController
  public static class PrivateController {
    @GetMapping("/private")
    public Map<String, String> privateData() {
      return Map.of("fact", "synthetic private host data");
    }
  }
}
