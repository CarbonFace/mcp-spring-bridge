package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.annotation.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * A trusted MVC principal must never be silently reinterpreted as an AI-supplied business argument.
 */
public class UnsafeInjectionRegistrationTest {
  @TempDir Path workspace;

  @Test
  void applicationRefusesAnEndpointThatWouldTurnTrustedPrincipalInjectionIntoModelInput() {
    new WebApplicationContextRunner()
        .withUserConfiguration(ServletMcpIntegrationTest.Host.class, Unsafe.class)
        .withPropertyValues(
            "mcp.bridge.enabled=true",
            "mcp.bridge.resource=https://fixture.invalid/mcp",
            "mcp.bridge.authorization-servers[0]=https://fixture.invalid/oauth",
            "mcp.bridge.storage=" + workspace.resolve("state").toAbsolutePath(),
            "mcp.bridge.files.directory=" + workspace.resolve("files").toAbsolutePath(),
            "mcp.bridge.files.cleanup-interval=PT1H")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("cannot become model input");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class Unsafe {
    @Bean
    UnsafeController unsafeController() {
      return new UnsafeController();
    }
  }

  @RestController
  public static class UnsafeController {
    @GetMapping("/unsafe-principal")
    @McpEndpoint(
        name = "unsafe_principal",
        description = "Must refuse unsafe identity injection",
        effect = Effect.READ,
        allowAuthenticated = true)
    public String principal(@AuthenticationPrincipal String username) {
      return username;
    }
  }
}
