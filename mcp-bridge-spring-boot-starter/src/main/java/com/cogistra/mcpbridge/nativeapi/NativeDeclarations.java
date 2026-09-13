package com.cogistra.mcpbridge.nativeapi;

import com.cogistra.mcpbridge.annotation.Effect;
import com.cogistra.mcpbridge.annotation.McpPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import org.springaicommunity.mcp.McpPredicates;
import org.springaicommunity.mcp.annotation.*;
import org.springaicommunity.mcp.provider.complete.SyncStatelessMcpCompleteProvider;
import org.springaicommunity.mcp.provider.prompt.SyncStatelessMcpPromptProvider;
import org.springaicommunity.mcp.provider.resource.SyncStatelessMcpResourceProvider;
import org.springaicommunity.mcp.provider.tool.SyncStatelessMcpToolProvider;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** Pure discovery/adapter. Call only after application singleton initialization. */
public final class NativeDeclarations {
  private NativeDeclarations() {}

  private static final List<Class<? extends Annotation>> DECLARATIONS =
      List.of(McpTool.class, McpResource.class, McpPrompt.class, McpComplete.class);

  public static NativeCatalog scan(ListableBeanFactory factory) {
    Map<String, Object> beans = new TreeMap<>();
    for (String name : factory.getBeanNamesForType(Object.class, true, false)) {
      if (name.startsWith("scopedTarget.")) continue;
      Class<?> type = factory.getType(name, false);
      if (type == null) continue;
      // Avoid forcing irrelevant lazy beans solely to inspect annotations. Initialized
      // proxies are inspected by target class below, without unwrapping their target.
      if (!relevant(type) && !org.springframework.aop.SpringProxy.class.isAssignableFrom(type))
        continue;
      Object bean = factory.getBean(name);
      if (relevant(AopUtils.getTargetClass(bean))
          || bean instanceof ToolCallback
          || bean instanceof ToolCallbackProvider) {
        if (!factory.isSingleton(name))
          throw new IllegalStateException(
              "Native MCP declarations require singleton beans: " + name);
        beans.put(name, bean);
      }
    }
    return scan(beans);
  }

  private static boolean relevant(Class<?> type) {
    if (ToolCallback.class.isAssignableFrom(type)
        || ToolCallbackProvider.class.isAssignableFrom(type)) return true;
    for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type))
      if (hasMcpDeclaration(method)) return true;
    return false;
  }

  private static boolean hasMcpDeclaration(Method method) {
    for (Class<? extends Annotation> a : DECLARATIONS)
      if (AnnotatedElementUtils.hasAnnotation(method, a)) return true;
    for (Annotation a : method.getDeclaredAnnotations())
      if (a.annotationType().getPackageName().equals("org.springaicommunity.mcp.annotation"))
        return true;
    return false;
  }

  public static NativeCatalog scan(Map<String, ?> beans) {
    List<NativeTool> tools = new ArrayList<>();
    List<NativeResource> resources = new ArrayList<>();
    List<NativeResourceTemplate> templates = new ArrayList<>();
    List<NativePrompt> prompts = new ArrayList<>();
    List<NativeCompletion> completions = new ArrayList<>();
    Set<Object> seenBeans = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<ToolCallback> seenCallbacks = Collections.newSetFromMap(new IdentityHashMap<>());
    for (var entry : new TreeMap<>(beans).entrySet()) {
      String name = entry.getKey();
      Object bean = Objects.requireNonNull(entry.getValue());
      if (!seenBeans.add(bean)) continue;
      Class<?> type = AopUtils.getTargetClass(bean);
      for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
        if (method.isBridge() || method.isSynthetic() || !hasMcpDeclaration(method)) continue;
        NativeSource source =
            new NativeSource(name, bean, type, method, NativeSource.Origin.ANNOTATION);
        validate(source);
        if (method.isAnnotationPresent(McpTool.class)) {
          SyncStatelessMcpToolProvider provider =
              new SyncStatelessMcpToolProvider(List.of(bean)) {
                @Override
                protected Method[] doGetClassMethods(Object ignored) {
                  return new Method[] {method};
                }

                @Override
                protected Class<? extends Throwable> doGetToolCallException() {
                  return NeverConverted.class;
                }
              };
          tools.add(new NativeTool(source, only(source, provider.getToolSpecifications())));
        } else if (method.isAnnotationPresent(McpResource.class)) {
          rejectWrite(source);
          var provider =
              new SyncStatelessMcpResourceProvider(List.of(bean)) {
                @Override
                protected Method[] doGetClassMethods(Object ignored) {
                  return new Method[] {method};
                }
              };
          if (McpPredicates.isUriTemplate(method.getAnnotation(McpResource.class).uri()))
            templates.add(
                new NativeResourceTemplate(
                    source, only(source, provider.getResourceTemplateSpecifications())));
          else
            resources.add(
                new NativeResource(source, only(source, provider.getResourceSpecifications())));
        } else if (method.isAnnotationPresent(McpPrompt.class)) {
          rejectWrite(source);
          var provider =
              new SyncStatelessMcpPromptProvider(List.of(bean)) {
                @Override
                protected Method[] doGetClassMethods(Object ignored) {
                  return new Method[] {method};
                }
              };
          prompts.add(new NativePrompt(source, only(source, provider.getPromptSpecifications())));
        } else if (method.isAnnotationPresent(McpComplete.class)) {
          rejectWrite(source);
          var provider =
              new SyncStatelessMcpCompleteProvider(List.of(bean)) {
                @Override
                protected Method[] doGetClassMethods(Object ignored) {
                  return new Method[] {method};
                }
              };
          completions.add(
              new NativeCompletion(source, only(source, provider.getCompleteSpecifications())));
        }
      }
      if (bean instanceof ToolCallback callback && seenCallbacks.add(callback))
        tools.add(
            callback(
                new NativeSource(name, bean, type, null, NativeSource.Origin.TOOL_CALLBACK),
                callback));
      if (bean instanceof ToolCallbackProvider provider) {
        ToolCallback[] declared =
            Objects.requireNonNull(
                provider.getToolCallbacks(), "ToolCallbackProvider returned null: " + name);
        for (ToolCallback callback : declared) {
          if (callback == null) throw new IllegalStateException("Null ToolCallback from " + name);
          if (seenCallbacks.add(callback))
            tools.add(
                callback(
                    new NativeSource(
                        name, bean, type, null, NativeSource.Origin.TOOL_CALLBACK_PROVIDER),
                    callback));
        }
      }
    }
    unique(tools.stream().map(x -> x.specification().tool().name()).toList(), "tool name");
    unique(
        resources.stream().map(x -> x.specification().resource().uri()).toList(), "resource URI");
    unique(
        templates.stream().map(x -> x.specification().resourceTemplate().uriTemplate()).toList(),
        "resource template URI");
    unique(prompts.stream().map(x -> x.specification().prompt().name()).toList(), "prompt name");
    unique(
        completions.stream().map(x -> x.specification().referenceKey().toString()).toList(),
        "completion reference");
    return new NativeCatalog(tools, resources, templates, prompts, completions);
  }

  private static void validate(NativeSource source) {
    Method method = source.method();
    long count = DECLARATIONS.stream().filter(method::isAnnotationPresent).count();
    if (count != 1)
      throw unsupported(
          source,
          "Exactly one directly declared native @McpTool, @McpResource, @McpPrompt or @McpComplete is supported; composed and session/client callback annotations need an explicit adapter");
    for (Annotation annotation : method.getDeclaredAnnotations())
      if (annotation
              .annotationType()
              .getPackageName()
              .equals("org.springaicommunity.mcp.annotation")
          && !DECLARATIONS.contains(annotation.annotationType()))
        throw unsupported(
            source,
            "Unsupported native callback annotation: "
                + annotation.annotationType().getSimpleName());
    if (AopUtils.isJdkDynamicProxy(source.bean()))
      throw unsupported(
          source,
          "JDK proxies cannot safely receive the target Method; enable class-based proxying (proxyTargetClass=true)");
    if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()))
      throw unsupported(source, "Native MCP methods must be public instance methods");
    if (AopUtils.isAopProxy(source.bean()) && Modifier.isFinal(method.getModifiers()))
      throw unsupported(source, "A final method bypasses its Spring proxy advice");
    if (!method.getDeclaringClass().isInstance(source.bean()))
      throw unsupported(
          source,
          "The actual Spring bean cannot receive this method; target unwrapping is forbidden");
    if (McpPredicates.isReactiveReturnType.test(method)
        || Future.class.isAssignableFrom(method.getReturnType())
        || CompletionStage.class.isAssignableFrom(method.getReturnType()))
      throw unsupported(
          source,
          "Reactive/future return types are not supported by the synchronous stateless transport");
    if (!McpPredicates.filterMethodWithBidirectionalParameters().test(method))
      throw unsupported(
          source,
          "Bidirectional session parameters are not supported by stateless transport; use McpTransportContext");
    for (Parameter p : method.getParameters()) {
      String n = p.getType().getName();
      if (n.startsWith("jakarta.servlet.")
          || n.startsWith("org.springframework.security.")
          || n.startsWith("com.cogistra.mcpbridge.api.BridgeIdentity")
          || n.equals("com.cogistra.mcpbridge.api.BridgePrincipal")
          || java.security.Principal.class.isAssignableFrom(p.getType())
          || n.equals("kotlin.coroutines.Continuation"))
        throw unsupported(
            source,
            "Servlet/security identity parameters cannot be model-supplied native arguments: "
                + p.getName());
      if (!p.isNamePresent()
          && !n.startsWith("io.modelcontextprotocol.")
          && !n.startsWith("org.springaicommunity.mcp."))
        throw unsupported(
            source,
            "Compile native tool classes with -parameters to preserve input argument names");
    }
  }

  private static void rejectWrite(NativeSource source) {
    McpPolicy policy = AnnotatedElementUtils.findMergedAnnotation(source.method(), McpPolicy.class);
    if (policy == null)
      policy = AnnotatedElementUtils.findMergedAnnotation(source.targetClass(), McpPolicy.class);
    if (policy != null && policy.effect() == Effect.WRITE)
      throw unsupported(
          source,
          "Resources, prompts and completions must be read-only; expose mutations as a Tool");
  }

  private static NativeTool callback(NativeSource source, ToolCallback callback) {
    var definition =
        Objects.requireNonNull(callback.getToolDefinition(), "Tool callback has no definition");
    var tool =
        McpSchema.Tool.builder()
            .name(definition.name())
            .description(definition.description())
            .inputSchema(McpJsonDefaults.getMapper(), definition.inputSchema())
            .build();
    var specification =
        SyncToolSpecification.builder()
            .tool(tool)
            .callHandler(
                (context, request) -> {
                  try {
                    String input =
                        McpJsonDefaults.getMapper()
                            .writeValueAsString(
                                request.arguments() == null ? Map.of() : request.arguments());
                    String output =
                        callback.call(
                            input,
                            new ToolContext(
                                Map.of(
                                    "exchange",
                                    context == null ? McpTransportContext.EMPTY : context)));
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(output == null ? "null" : output)
                        .build();
                  } catch (RuntimeException e) {
                    throw e;
                  } catch (Exception e) {
                    throw new IllegalStateException(
                        "Cannot serialize native callback arguments", e);
                  }
                })
            .build();
    return new NativeTool(source, specification);
  }

  private static <T> T only(NativeSource source, List<T> results) {
    if (results.size() != 1)
      throw unsupported(source, "The native provider did not produce exactly one capability");
    return results.get(0);
  }

  private static void unique(List<String> keys, String kind) {
    Set<String> seen = new HashSet<>();
    for (String key : keys)
      if (key == null || key.isBlank() || !seen.add(key))
        throw new IllegalStateException("Invalid or duplicate native MCP " + kind + ": " + key);
  }

  private static IllegalStateException unsupported(NativeSource source, String reason) {
    return new IllegalStateException(
        "Native MCP declaration "
            + source.beanName()
            + "#"
            + source.method().getName()
            + ": "
            + reason);
  }

  /** No ordinary method exception is translated before the central error and audit boundary. */
  private static final class NeverConverted extends Throwable {}
}
