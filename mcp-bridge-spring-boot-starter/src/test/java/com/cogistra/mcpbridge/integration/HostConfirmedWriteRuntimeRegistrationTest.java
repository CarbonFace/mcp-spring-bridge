package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.api.BridgeIdentityResolver;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.*;

/** Unsafe confirmation declarations must stop startup before a callable endpoint is published. */
public class HostConfirmedWriteRuntimeRegistrationTest {
  @TempDir Path workspace;

  @Test
  void aHostConfirmedWriteCannotFallBackToTheFileWorkflowWhenItsRuntimeIsMissing() {
    runner()
        .withBean(MissingRuntime.class, MissingRuntime::new)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("Missing confirmed write runtime for host_create");
            });
  }

  @Test
  void aReadDeclarationCannotExposeTheHostWriteLifecycle() {
    runner()
        .withBean(ReadRuntime.class, ReadRuntime::new)
        .withBean(
            HostConfirmedWriteRuntimeIntegrationTest.FixtureRuntime.class,
            HostConfirmedWriteRuntimeIntegrationTest.FixtureRuntime::new)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "A confirmed runtime requires a declared WRITE capability: host_create");
            });
  }

  private WebApplicationContextRunner runner() {
    Path directory = workspace.resolve(UUID.randomUUID().toString());
    return new WebApplicationContextRunner()
        .withUserConfiguration(Base.class)
        .withPropertyValues(
            "mcp.bridge.resource=https://fixture.invalid/mcp",
            "mcp.bridge.authorization-servers[0]=https://fixture.invalid/oauth",
            "mcp.bridge.storage=" + directory.resolve("state").toAbsolutePath(),
            "mcp.bridge.files.directory=" + directory.resolve("files").toAbsolutePath());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class Base {
    @Bean
    ServletMcpIntegrationTest.Accounts accounts() {
      return new ServletMcpIntegrationTest.Accounts();
    }

    @Bean(name = "bridgeJwtDecoder")
    JwtDecoder decoder(ServletMcpIntegrationTest.Accounts accounts) {
      return accounts::decode;
    }

    @Bean
    BridgeIdentityResolver identities(ServletMcpIntegrationTest.Accounts accounts) {
      return accounts::resolve;
    }
  }

  @RestController
  public static class MissingRuntime {
    @PostMapping("/invalid/write")
    @McpEndpoint(
        name = "host_create",
        description = "Missing runtime fixture",
        effect = Effect.WRITE,
        confirmationRuntime = true,
        scopes = "records:write")
    public String create() {
      throw new AssertionError("Registration must never execute business writes");
    }
  }

  @RestController
  public static class ReadRuntime {
    @GetMapping("/invalid/read")
    @McpEndpoint(
        name = "host_create",
        description = "Invalid read fixture",
        effect = Effect.READ,
        confirmationRuntime = true,
        scopes = "records:read")
    public String read() {
      throw new AssertionError("Invalid capability must never be callable");
    }
  }
}
