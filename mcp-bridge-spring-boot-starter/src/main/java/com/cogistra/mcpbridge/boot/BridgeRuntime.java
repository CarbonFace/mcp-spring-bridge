package com.cogistra.mcpbridge.boot;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.audit.*;
import com.cogistra.mcpbridge.binding.*;
import com.cogistra.mcpbridge.files.FileUploadService;
import com.cogistra.mcpbridge.guidance.*;
import com.cogistra.mcpbridge.nativeapi.*;
import com.cogistra.mcpbridge.registry.*;
import com.cogistra.mcpbridge.security.BridgeInvocationContext;
import com.cogistra.mcpbridge.transport.GuardedStatelessTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.*;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

public final class BridgeRuntime
    implements SmartInitializingSingleton, AutoCloseable, GuardedStatelessTransport.Visibility {
  private final ConfigurableApplicationContext context;
  private final ObjectMapper json;
  private final BridgeProperties properties;
  private final BridgeToolCatalog tools;
  private final BridgePolicyGate gate;
  private final BridgeInvocationContext identities;
  private final WebMvcStatelessServerTransport transport;
  private final BridgeSchemas schemas;
  private final AuditRepository audit;
  private final Map<String, BridgePolicyGate.Policy> resources = new HashMap<>(),
      templates = new HashMap<>(),
      prompts = new HashMap<>();
  private McpStatelessSyncServer server;

  public BridgeRuntime(
      ConfigurableApplicationContext context,
      BridgeJson json,
      BridgeProperties properties,
      BridgeToolCatalog tools,
      BridgePolicyGate gate,
      BridgeInvocationContext identities,
      WebMvcStatelessServerTransport transport,
      BridgeSchemas schemas,
      AuditRepository audit) {
    this.context = context;
    this.json = json.mapper();
    this.properties = properties;
    this.tools = tools;
    this.gate = gate;
    this.identities = identities;
    this.transport = transport;
    this.schemas = schemas;
    this.audit = audit;
  }

  public void afterSingletonsInstantiated() {
    if (!context.getBeansOfType(McpStatelessSyncServer.class).isEmpty()
        || !context.getBeansOfType(McpStatelessAsyncServer.class).isEmpty()
        || !context.getBeansOfType(McpSyncServer.class).isEmpty()
        || !context.getBeansOfType(McpAsyncServer.class).isEmpty())
      throw new IllegalStateException(
          "A second MCP server bypasses the managed registry. Remove independent server auto-configuration and use this starter as the only server.");
    RequestMappingHandlerMapping mappings =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    List<BridgeContractAdapter> adapters =
        context.getBeanProvider(BridgeContractAdapter.class).orderedStream().toList();
    context
        .getBeanProvider(BridgeConfirmedWriteRuntime.class)
        .orderedStream()
        .forEach(tools::registerRuntime);
    Validator validator = context.getBean(Validator.class);
    BridgeFileStore files =
        context.getBeanProvider(BridgeFileStore.class).getIfAvailable(DisabledFiles::new);
    mappings
        .getHandlerMethods()
        .forEach(
            (mapping, unresolved) -> {
              var handler = unresolved.createWithResolvedBean();
              McpEndpoint annotation =
                  AnnotatedElementUtils.findMergedAnnotation(
                      handler.getMethod(), McpEndpoint.class);
              if (annotation == null) return;
              Set<String> paths = mapping.getPatternValues();
              var verbs = mapping.getMethodsCondition().getMethods();
              if (paths.size() != 1 || verbs.size() != 1)
                throw new IllegalStateException(
                    "MCP endpoint needs one explicit HTTP method and path (split aliases or use a contract adapter): "
                        + handler);
              if (!mapping.getParamsCondition().isEmpty()
                  || !mapping.getHeadersCondition().isEmpty()
                  || mapping.getCustomCondition() != null)
                throw new IllegalStateException(
                    "MCP cannot discard HTTP params/headers/custom mapping conditions; use an explicit compatible facade: "
                        + handler);
              String route = paths.iterator().next();
              if (route.contains("*")
                  || route.contains("?")
                  || java.util.regex.Pattern.compile("\\{[^}]*:").matcher(route).find())
                throw new IllegalStateException(
                    "MCP regex/wildcard routes need an explicit compatible facade: " + handler);
              EndpointPlan plan =
                  new EndpointPlan(
                      handler,
                      annotation,
                      verbs.iterator().next().name(),
                      paths.iterator().next(),
                      json,
                      schemas,
                      validator,
                      files,
                      adapters,
                      properties.getMaxExportBytes());
              var policy =
                  gate.policy(
                      annotation.name(), handler.getBean(), handler.getMethod(), annotation);
              gate.bindArguments(policy, plan::permissionArguments);
              gate.bindCanonicalArguments(policy, plan::canonicalPermissionArguments);
              Tool definition =
                  tools.tool(
                      annotation.name(),
                      annotation.description(),
                      plan.inputSchema(),
                      policy.effect() == Effect.READ);
              if (annotation.output() != Void.class && policy.effect() == Effect.READ)
                definition = tools.withOutput(definition, schemas.schema(annotation.output()));
              tools.register(
                  new BridgeToolCatalog.Capability(
                      definition,
                      policy,
                      (args, p) -> plan.validate(args, p.identity()),
                      (c, args, p) -> plan.invoke(args, p),
                      (c, args, p) -> plan.invokeCanonical(args, p),
                      annotation.confirmationRuntime()));
            });
    NativeCatalog nativeCatalog = NativeDeclarations.scan(context.getBeanFactory());
    for (var item : nativeCatalog.tools()) {
      var spec = item.specification();
      var source = item.source();
      var policy = gate.policy(spec.tool().name(), source.bean(), source.method(), null);
      tools.register(
          new BridgeToolCatalog.Capability(
              spec.tool(),
              policy,
              (args, p) -> args,
              (c, args, p) ->
                  spec.callHandler().apply(c, new CallToolRequest(spec.tool().name(), args)),
              (c, args, p) ->
                  spec.callHandler().apply(c, new CallToolRequest(spec.tool().name(), args))));
    }
    FileUploadService uploads = context.getBeanProvider(FileUploadService.class).getIfAvailable();
    var fileProperties =
        context
            .getBeanProvider(com.cogistra.mcpbridge.files.BridgeFilesProperties.class)
            .getIfAvailable();
    if (uploads != null
        && fileProperties != null
        && ((long) fileProperties.getMaxChunkBytes() + 2) / 3 * 4 + 1024
            > properties.getMaxArgumentBytes())
      throw new IllegalArgumentException(
          "mcp.bridge.files.max-chunk-bytes must fit base64 plus metadata within mcp.bridge.max-argument-bytes");
    tools.registerSystem(uploads);
    boolean guidancePublished =
        tools.registerGuidance(
            new BridgeGuidanceRegistry(
                context.getBeanProvider(BridgeGuidanceProvider.class).orderedStream().toList(),
                tools::visible,
                context.getBeanProvider(BridgeFileStore.class).getIfAvailable(),
                properties.getMaxExportBytes()));
    var resourceSpecs = new ArrayList<McpStatelessServerFeatures.SyncResourceSpecification>();
    for (var item : nativeCatalog.resources()) {
      var spec = item.specification();
      var source = item.source();
      var policy = readPolicy("resource." + spec.resource().name(), source.bean(), source.method());
      put(resources, spec.resource().uri(), policy);
      resourceSpecs.add(
          new McpStatelessServerFeatures.SyncResourceSpecification(
              spec.resource(),
              (c, r) ->
                  read(c, policy, Map.of("uri", r.uri()), () -> spec.readHandler().apply(c, r))));
    }
    var templateSpecs =
        new ArrayList<McpStatelessServerFeatures.SyncResourceTemplateSpecification>();
    for (var item : nativeCatalog.resourceTemplates()) {
      var spec = item.specification();
      var source = item.source();
      var policy =
          readPolicy("resource." + spec.resourceTemplate().name(), source.bean(), source.method());
      put(templates, spec.resourceTemplate().uriTemplate(), policy);
      templateSpecs.add(
          new McpStatelessServerFeatures.SyncResourceTemplateSpecification(
              spec.resourceTemplate(),
              (c, r) ->
                  read(c, policy, Map.of("uri", r.uri()), () -> spec.readHandler().apply(c, r))));
    }
    var promptSpecs = new ArrayList<McpStatelessServerFeatures.SyncPromptSpecification>();
    for (var item : nativeCatalog.prompts()) {
      var spec = item.specification();
      var source = item.source();
      var policy = readPolicy("prompt." + spec.prompt().name(), source.bean(), source.method());
      put(prompts, spec.prompt().name(), policy);
      promptSpecs.add(
          new McpStatelessServerFeatures.SyncPromptSpecification(
              spec.prompt(),
              (c, r) ->
                  read(
                      c,
                      policy,
                      new LinkedHashMap<>(r.arguments() == null ? Map.of() : r.arguments()),
                      () -> spec.promptHandler().apply(c, r))));
    }
    var completionSpecs = new ArrayList<McpStatelessServerFeatures.SyncCompletionSpecification>();
    for (var item : nativeCatalog.completions()) {
      var spec = item.specification();
      var source = item.source();
      var policy =
          readPolicy("completion." + source.method().getName(), source.bean(), source.method());
      completionSpecs.add(
          new McpStatelessServerFeatures.SyncCompletionSpecification(
              spec.referenceKey(),
              (c, r) ->
                  read(
                      c,
                      policy,
                      json.convertValue(
                          r,
                          new com.fasterxml.jackson.core.type.TypeReference<
                              Map<String, Object>>() {}),
                      () -> spec.completionHandler().apply(c, r))));
    }
    gate.validateConfiguredPolicies();
    server =
        McpServer.sync(new GuardedStatelessTransport(transport, identities, this))
            .jsonMapper(
                new JacksonMcpJsonMapper(context.getBean(BridgeJson.class).protocolMapper()))
            .jsonSchemaValidator(
                new BridgeSdkSchemaValidator(context.getBean(BridgeJson.class).protocolMapper()))
            .strictToolNameValidation(true)
            .serverInfo(properties.getName(), "0.1.0")
            .instructions(
                "For every business write: prepare, show the exact preview, obtain explicit user confirmation, then submit that operationId/revision/payloadHash. An edited preview always needs new confirmation. UNKNOWN outcomes must be reconciled, never blindly repeated. File uploads only stage files; importing them is a separate confirmed business action."
                    + (guidancePublished ? BridgeGuidanceRegistry.NAVIGATION : ""))
            .capabilities(
                ServerCapabilities.builder()
                    .tools(false)
                    .resources(false, false)
                    .prompts(false)
                    .completions()
                    .build())
            .tools(tools.specifications())
            .resources(resourceSpecs)
            .resourceTemplates(templateSpecs)
            .prompts(promptSpecs)
            .completions(completionSpecs)
            .build();
  }

  private BridgePolicyGate.Policy readPolicy(
      String name, Object bean, java.lang.reflect.Method method) {
    var policy = gate.policy(name, bean, method, null);
    // Resource/prompt/completion methods cannot participate in tool confirmation; explicit WRITE is
    // invalid.
    McpPolicy annotation = AnnotatedElementUtils.findMergedAnnotation(method, McpPolicy.class);
    if (annotation == null)
      annotation =
          AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), McpPolicy.class);
    var configured = properties.getTools().get(name);
    if ((annotation != null && annotation.effect() == Effect.WRITE)
        || (configured != null && configured.getEffect() == Effect.WRITE))
      throw new IllegalStateException(
          "A resource/prompt/completion must be read-only; publish writes as a tool: " + name);
    return policy;
  }

  private <T> T read(
      McpTransportContext context,
      BridgePolicyGate.Policy policy,
      Map<String, Object> args,
      java.util.concurrent.Callable<T> action) {
    try {
      return identities.call(
          context,
          p -> {
            try {
              gate.authorizeReadBoundary(policy, p, args);
              audit.append(
                  AuditEvent.create(
                      p.identity(),
                      policy.name(),
                      null,
                      AuditEvent.Phase.READ,
                      AuditEvent.Outcome.ALLOWED,
                      null));
              T result = action.call();
              if (json.writeValueAsBytes(result).length > properties.getMaxResultBytes())
                throw new BridgeException("RESULT_TOO_LARGE", "Use a file export for this result");
              audit.append(
                  AuditEvent.create(
                      p.identity(),
                      policy.name(),
                      null,
                      AuditEvent.Phase.READ,
                      AuditEvent.Outcome.SUCCEEDED,
                      null));
              return result;
            } catch (Exception failure) {
              try {
                audit.append(
                    AuditEvent.create(
                        p.identity(),
                        policy.name(),
                        null,
                        AuditEvent.Phase.READ,
                        failure instanceof org.springframework.security.access.AccessDeniedException
                            ? AuditEvent.Outcome.DENIED
                            : AuditEvent.Outcome.FAILED,
                        "ACCESS_OR_READ_FAILED"));
              } catch (RuntimeException ignored) {
              }
              throw failure;
            }
          });
    } catch (Exception failure) {
      throw new BridgeException(
          "FORBIDDEN_OR_FAILED", "Resource or prompt access could not be completed");
    }
  }

  private void put(
      Map<String, BridgePolicyGate.Policy> map, String key, BridgePolicyGate.Policy policy) {
    if (map.putIfAbsent(key, policy) != null)
      throw new IllegalStateException("Duplicate MCP resource/prompt: " + key);
  }

  public boolean tool(String name, BridgePrincipal p) {
    return tools.visible(name, p);
  }

  public boolean resource(String uri, BridgePrincipal p) {
    return visible(resources, uri, p);
  }

  public boolean template(String uri, BridgePrincipal p) {
    return visible(templates, uri, p);
  }

  public boolean prompt(String name, BridgePrincipal p) {
    return visible(prompts, name, p);
  }

  private boolean visible(
      Map<String, BridgePolicyGate.Policy> policies, String name, BridgePrincipal p) {
    var policy = policies.get(name);
    return policy != null && gate.visible(policy, p);
  }

  public void close() {
    if (server != null) server.closeGracefully();
  }

  private static final class DisabledFiles implements BridgeFileStore {
    public FileArtifact store(
        BridgeIdentity identity, String filename, String mediaType, java.io.InputStream stream) {
      try {
        stream.close();
      } catch (java.io.IOException ignored) {
      }
      throw new BridgeException("FILES_DISABLED", "File support is disabled in this application");
    }

    public ArtifactContent open(BridgeIdentity identity, String fileId) {
      throw new BridgeException("FILES_DISABLED", "File support is disabled in this application");
    }

    public void delete(BridgeIdentity identity, String fileId) {
      throw new BridgeException("FILES_DISABLED", "File support is disabled in this application");
    }
  }
}
