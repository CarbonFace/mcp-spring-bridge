package com.cogistra.mcpbridge.nativeapi;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.annotation.*;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springaicommunity.mcp.annotation.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class NativeAuthorizationTest {
  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  void login(boolean allowed) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "employee",
                "",
                allowed ? List.of(new SimpleGrantedAuthority("finance:read")) : List.of()));
  }

  @Test
  void everyNativeCapabilityInvokesTheSameSpringSecuredBean() {
    try (var context = new AnnotationConfigApplicationContext(SecuredConfiguration.class)) {
      NativeCatalog catalog = NativeDeclarations.scan(context);
      var tool = catalog.tools().get(0).specification();
      var resource = catalog.resources().get(0).specification();
      var template = catalog.resourceTemplates().get(0).specification();
      var prompt = catalog.prompts().get(0).specification();
      var complete = catalog.completions().get(0).specification();
      var toolRequest = new McpSchema.CallToolRequest("private_balance", Map.of());
      var resourceRequest = new McpSchema.ReadResourceRequest("private://summary");
      var templateRequest = new McpSchema.ReadResourceRequest("private://item/42");
      var promptRequest = new McpSchema.GetPromptRequest("private_prompt", Map.of());
      var completeRequest =
          new McpSchema.CompleteRequest(
              new McpSchema.PromptReference("private_prompt"),
              new McpSchema.CompleteRequest.CompleteArgument("name", "a"));
      login(false);
      assertThatThrownBy(() -> tool.callHandler().apply(McpTransportContext.EMPTY, toolRequest))
          .hasRootCauseInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () -> resource.readHandler().apply(McpTransportContext.EMPTY, resourceRequest))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () -> template.readHandler().apply(McpTransportContext.EMPTY, templateRequest))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () -> prompt.promptHandler().apply(McpTransportContext.EMPTY, promptRequest))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () -> complete.completionHandler().apply(McpTransportContext.EMPTY, completeRequest))
          .isInstanceOf(RuntimeException.class);
      login(true);
      assertThat(tool.callHandler().apply(McpTransportContext.EMPTY, toolRequest).content())
          .contains(new McpSchema.TextContent("private-balance"));
      assertThat(
              resource
                  .readHandler()
                  .apply(McpTransportContext.EMPTY, resourceRequest)
                  .contents()
                  .toString())
          .contains("private-summary");
      assertThat(
              template
                  .readHandler()
                  .apply(McpTransportContext.EMPTY, templateRequest)
                  .contents()
                  .toString())
          .contains("private-item-42");
      assertThat(
              prompt
                  .promptHandler()
                  .apply(McpTransportContext.EMPTY, promptRequest)
                  .messages()
                  .toString())
          .contains("private-prompt");
      assertThat(
              complete
                  .completionHandler()
                  .apply(McpTransportContext.EMPTY, completeRequest)
                  .completion()
                  .values())
          .containsExactly("private-customer");
    }
  }

  @Test
  void unsupportedAsyncJdkProxyAndDuplicateToolsFailBeforePublication() {
    assertThatThrownBy(() -> NativeDeclarations.scan(Map.of("async", new AsyncDeclaration())))
        .hasMessageContaining("Reactive/future");
    ProxyFactory factory = new ProxyFactory(new InterfaceDeclaration());
    factory.setProxyTargetClass(false);
    assertThatThrownBy(() -> NativeDeclarations.scan(Map.of("jdk", factory.getProxy())))
        .hasMessageContaining("JDK proxies");
    assertThatThrownBy(
            () ->
                NativeDeclarations.scan(
                    Map.of("a", new InterfaceDeclaration(), "b", new InterfaceDeclaration())))
        .hasMessageContaining("duplicate native MCP tool");
    assertThatThrownBy(() -> NativeDeclarations.scan(Map.of("writeResource", new WriteResource())))
        .hasMessageContaining("must be read-only");
  }

  @Test
  void explicitCallbacksRetainTextAndFailuresWhileOrdinaryToolAnnotationsStayPrivate() {
    ToolCallback text = new Callback("text", "{\"amount\":12}", false),
        failed = new Callback("failed", "", true);
    var catalog =
        NativeDeclarations.scan(
            Map.of(
                "ordinary",
                new OrdinaryTool(),
                "provider",
                ToolCallbackProvider.from(text, failed),
                "sameCallback",
                text));
    assertThat(catalog.tools()).hasSize(2);
    var first =
        catalog.tools().stream()
            .filter(x -> x.specification().tool().name().equals("text"))
            .findFirst()
            .orElseThrow();
    assertThat(
            first
                .specification()
                .callHandler()
                .apply(McpTransportContext.EMPTY, new McpSchema.CallToolRequest("text", Map.of()))
                .content())
        .containsExactly(new McpSchema.TextContent("{\"amount\":12}"));
    var failure =
        catalog.tools().stream()
            .filter(x -> x.specification().tool().name().equals("failed"))
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                failure
                    .specification()
                    .callHandler()
                    .apply(
                        McpTransportContext.EMPTY,
                        new McpSchema.CallToolRequest("failed", Map.of())))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity(proxyTargetClass = true)
  static class SecuredConfiguration {
    @Bean
    SecuredDeclarations declarations() {
      return new SecuredDeclarations();
    }
  }

  @McpPolicy(effect = Effect.READ)
  @PreAuthorize("hasAuthority('finance:read')")
  public static class SecuredDeclarations {
    @McpTool(name = "private_balance", description = "Private balance")
    public String balance() {
      return "private-balance";
    }

    @McpResource(uri = "private://summary")
    public String summary() {
      return "private-summary";
    }

    @McpResource(uri = "private://item/{id}")
    public String item(String id) {
      return "private-item-" + id;
    }

    @McpPrompt(name = "private_prompt")
    public String prompt() {
      return "private-prompt";
    }

    @McpComplete(prompt = "private_prompt")
    public List<String> complete(McpSchema.CompleteRequest request) {
      return List.of("private-customer");
    }
  }

  public static class AsyncDeclaration {
    @McpTool
    public CompletableFuture<String> later() {
      return CompletableFuture.completedFuture("x");
    }
  }

  public interface Example {
    String value();
  }

  public static class InterfaceDeclaration implements Example {
    @McpTool(name = "duplicate")
    public String value() {
      return "x";
    }
  }

  public static class WriteResource {
    @McpResource(uri = "test://write")
    @McpPolicy(effect = Effect.WRITE)
    public String write() {
      return "x";
    }
  }

  public static class OrdinaryTool {
    @Tool
    public String secret() {
      return "not published";
    }
  }

  record Callback(String name, String output, boolean fail) implements ToolCallback {
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name(name)
          .description(name)
          .inputSchema("{\"type\":\"object\",\"properties\":{}}")
          .build();
    }

    public String call(String input) {
      if (fail) throw new AccessDeniedException("Denied");
      return output;
    }
  }
}
