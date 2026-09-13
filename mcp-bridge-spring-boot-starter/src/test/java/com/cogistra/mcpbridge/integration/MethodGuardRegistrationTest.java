package com.cogistra.mcpbridge.integration;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.api.BridgeIdentityResolver;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springaicommunity.mcp.annotation.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Unsafe method guards and ineffective security configuration must stop actual application startup.
 */
public class MethodGuardRegistrationTest {
  @TempDir Path workspace;

  @ParameterizedTest
  @ValueSource(classes = {SecuredMethod.class, JsrMethod.class})
  void aDeclaredButDisabledMethodGuardCannotBeSilentlyExported(Class<?> candidate) {
    withCandidate(runner(), candidate)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("method security is declared but not active");
            });
  }

  @ParameterizedTest
  @ValueSource(
      classes = {BeforeFiltered.class, AfterFiltered.class, InheritedAfterAuthorized.class})
  void filteringOrInheritedComposedPostAuthorizationCannotBeSilentlyDropped(Class<?> candidate) {
    withCandidate(runner().withUserConfiguration(Enabled.class), candidate)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("cannot safely replay snapshots or receipts");
            });
  }

  @Test
  void configurationForGeneratedSubmitNameDoesNotPretendToDisableItsBusinessTool() {
    withCandidate(runner().withUserConfiguration(Enabled.class), SecuredMethod.class)
        .withPropertyValues("mcp.bridge.tools.secured_guard_submit.enabled=false")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "Unknown MCP policy configuration keys: [secured_guard_submit]");
            });
  }

  @Test
  void differentResourceUrisCannotShareAnAmbiguousPolicyConfigurationKey() {
    withCandidate(runner(), DuplicateResources.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("duplicate MCP policy name: resource.shared");
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

  private <T> WebApplicationContextRunner withCandidate(
      WebApplicationContextRunner runner, Class<T> candidate) {
    return runner.withBean(
        "candidate",
        candidate,
        () -> {
          try {
            return candidate.getDeclaredConstructor().newInstance();
          } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
          }
        });
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

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity(proxyTargetClass = true, securedEnabled = true, jsr250Enabled = true)
  static class Enabled {}

  public static class SecuredMethod {
    @McpTool(name = "secured_guard", description = "Secured fixture")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    @Secured("ROLE_EDITOR")
    public String write() {
      return "must not execute during startup";
    }
  }

  public static class JsrMethod {
    @McpTool(name = "jsr_guard", description = "JSR fixture")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    @RolesAllowed("EDITOR")
    public String write() {
      return "must not execute during startup";
    }
  }

  public static class BeforeFiltered {
    @McpTool(name = "pre_filtered", description = "Unsafe filtered fixture")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    @PreFilter("true")
    public String write(@McpArg(name = "items") List<String> items) {
      return "must not execute";
    }
  }

  public static class AfterFiltered {
    @McpTool(name = "post_filtered", description = "Unsafe filtered receipt")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    @PostFilter("true")
    public List<String> write() {
      return List.of("must not execute");
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @PostAuthorize("true")
  @interface GuardAfter {}

  @GuardAfter
  public abstract static class AfterAuthorizedBase {}

  public static class InheritedAfterAuthorized extends AfterAuthorizedBase {
    @McpTool(name = "post_authorized", description = "Unsafe inherited post guard")
    @McpPolicy(effect = Effect.WRITE, scopes = "records:write")
    public String write() {
      return "must not execute";
    }
  }

  public static class DuplicateResources {
    @McpResource(name = "shared", uri = "fixture://first")
    @McpPolicy(effect = Effect.READ, allowAuthenticated = true)
    public McpSchema.ReadResourceResult first() {
      return new McpSchema.ReadResourceResult(List.of());
    }

    @McpResource(name = "shared", uri = "fixture://second")
    @McpPolicy(effect = Effect.READ, allowAuthenticated = true)
    public McpSchema.ReadResourceResult second() {
      return new McpSchema.ReadResourceResult(List.of());
    }
  }
}
