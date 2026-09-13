package com.cogistra.mcpbridge.registry;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.boot.BridgeProperties;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.*;
import org.springframework.security.core.annotation.SecurityAnnotationScanners;
import org.springframework.security.core.context.SecurityContextHolder;

/** Configuration only restricts; actual Spring method security remains on the invoked proxy. */
public final class BridgePolicyGate {
  private final BridgeProperties properties;
  private final List<BridgeAuthorization> authorizers;
  private final Map<String, List<MethodInterceptor>> beforeGuards = new HashMap<>();
  private final Set<String> registeredPolicies = new HashSet<>();
  private static final Object AUTHORIZED = new Object();
  private final Map<String, ArgumentResolver> argumentResolvers = new HashMap<>();
  private final Map<String, ArgumentResolver> canonicalResolvers = new HashMap<>();
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final ApplicationContext application;
  private final List<BridgeDiscoveryPolicy> discovery;

  public BridgePolicyGate(
      BridgeProperties properties,
      List<BridgeAuthorization> authorizers,
      ApplicationContext context) {
    this.properties = properties;
    this.authorizers = List.copyOf(authorizers);
    this.application = context;
    this.discovery = context.getBeanProvider(BridgeDiscoveryPolicy.class).orderedStream().toList();
    this.json = context.getBean(com.cogistra.mcpbridge.boot.BridgeJson.class).mapper();
  }

  public Policy policy(String name, Object bean, Method method, McpEndpoint endpoint) {
    registerPolicy(name);
    var configuration = properties.getTools().getOrDefault(name, new BridgeProperties.ToolPolicy());
    McpPolicy annotation =
        method == null ? null : AnnotatedElementUtils.findMergedAnnotation(method, McpPolicy.class);
    if (annotation == null && method != null)
      annotation =
          AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), McpPolicy.class);
    Class<?> target =
        method == null ? null : org.springframework.aop.support.AopUtils.getTargetClass(bean);
    PreAuthorize security =
        method == null
            ? null
            : SecurityAnnotationScanners.requireUnique(PreAuthorize.class).scan(method, target);
    List<MethodInterceptor> guards =
        method == null ? List.of() : methodGuards(name, bean, method, target);
    beforeGuards.put(name, guards);
    boolean methodGuard = !guards.isEmpty();
    Effect effect =
        endpoint != null
            ? endpoint.effect()
            : annotation != null ? annotation.effect() : Effect.WRITE;
    if ((endpoint != null || annotation != null)
        && effect == Effect.WRITE
        && configuration.getEffect() == Effect.READ)
      throw new IllegalStateException(
          "Configuration cannot remove confirmation from a declared WRITE capability: " + name);
    if (configuration.getEffect() != null) effect = configuration.getEffect();
    Set<String> scopes = new HashSet<>(configuration.getScopes());
    if (endpoint != null) scopes.addAll(List.of(endpoint.scopes()));
    if (annotation != null) scopes.addAll(List.of(annotation.scopes()));
    boolean explicit =
        configuration.isAllowAuthenticated()
            || (endpoint != null && endpoint.allowAuthenticated())
            || (annotation != null && annotation.allowAuthenticated());
    if (configuration.isEnabled()
        && !explicit
        && !methodGuard
        && configuration.getAuthorities().isEmpty()
        && scopes.isEmpty()
        && authorizers.isEmpty())
      throw new IllegalStateException(
          "No permission policy for MCP capability "
              + name
              + "; declare business method security, required scopes/authorities, a host authorization SPI, or explicit allowAuthenticated.");
    return new Policy(
        name,
        effect,
        configuration.isEnabled(),
        Set.copyOf(scopes),
        Set.copyOf(configuration.getAuthorities()),
        bean,
        method,
        security,
        explicit);
  }

  public void authorize(Policy policy, BridgePrincipal principal, Map<String, Object> arguments) {
    authorize(policy, principal, arguments, false);
  }

  public void authorizeCanonical(
      Policy policy, BridgePrincipal principal, Map<String, Object> arguments) {
    authorize(policy, principal, arguments, true);
  }

  /**
   * Native read providers bind URI/request parameters and invoke the secured Spring proxy
   * themselves.
   */
  public void authorizeReadBoundary(
      Policy policy, BridgePrincipal principal, Map<String, Object> arguments) {
    requireAvailable(policy, principal);
    for (BridgeAuthorization rule : authorizers)
      rule.authorize(
          new BridgeCall(principal, policy.name(), Collections.unmodifiableMap(arguments)));
  }

  private void authorize(
      Policy policy, BridgePrincipal principal, Map<String, Object> arguments, boolean canonical) {
    requireAvailable(policy, principal);
    if (!beforeGuards.getOrDefault(policy.name(), List.of()).isEmpty()) {
      Object[] values;
      ArgumentResolver resolver =
          (canonical ? canonicalResolvers : argumentResolvers).get(policy.name());
      try {
        values =
            resolver != null
                ? resolver.resolve(arguments, principal)
                : nativeArguments(policy, arguments);
      } catch (RuntimeException invalid) {
        throw new BridgeException(
            "INVALID_ARGUMENT", "Input cannot be bound to the declared business contract");
      }
      try {
        checkMethodGuards(policy, principal, values);
      } catch (RuntimeException denied) {
        throw new BridgeException(
            "FORBIDDEN", "The current business permission does not allow this capability");
      }
    }
    for (BridgeAuthorization authorization : authorizers)
      authorization.authorize(
          new BridgeCall(principal, policy.name(), Collections.unmodifiableMap(arguments)));
  }

  public void requireAvailable(Policy policy, BridgePrincipal principal) {
    if (!coarse(policy, principal))
      throw new BridgeException(
          "FORBIDDEN", "This capability is not available for this identity and client");
  }

  public void bindArguments(Policy policy, ArgumentResolver resolver) {
    argumentResolvers.put(policy.name(), resolver);
  }

  public void bindCanonicalArguments(Policy policy, ArgumentResolver resolver) {
    canonicalResolvers.put(policy.name(), resolver);
  }

  @FunctionalInterface
  public interface ArgumentResolver {
    Object[] resolve(Map<String, Object> arguments, BridgePrincipal principal);
  }

  private Object[] nativeArguments(Policy policy, Map<String, Object> arguments) {
    Parameter[] parameters = policy.method().getParameters();
    Object[] values = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      Parameter parameter = parameters[i];
      if (parameter.getType().getName().startsWith("io.modelcontextprotocol.")
          || parameter.getType().getName().startsWith("org.springaicommunity.mcp.")) continue;
      var named = parameter.getAnnotation(org.springaicommunity.mcp.annotation.McpArg.class);
      String key = named == null || named.name().isBlank() ? parameter.getName() : named.name();
      values[i] =
          json.convertValue(
              arguments.get(key), json.constructType(parameter.getParameterizedType()));
    }
    return values;
  }

  public Policy systemPolicy(String name) {
    registerPolicy(name);
    var config = properties.getTools().getOrDefault(name, new BridgeProperties.ToolPolicy());
    return new Policy(
        name,
        Effect.READ,
        config.isEnabled(),
        Set.copyOf(config.getScopes()),
        Set.copyOf(config.getAuthorities()),
        null,
        null,
        null,
        true);
  }

  public boolean visible(Policy policy, BridgePrincipal principal) {
    if (!coarse(policy, principal)) return false;
    var decisions = discovery.stream().map(rule -> rule.visible(policy.name(), principal)).toList();
    if (decisions.contains(BridgeDiscoveryPolicy.Decision.DENY)) return false;
    if (decisions.contains(BridgeDiscoveryPolicy.Decision.ALLOW)) return true;
    if (beforeGuards.getOrDefault(policy.name(), List.of()).isEmpty())
      return authorizers.isEmpty() || !discovery.isEmpty();
    // Role/authority checks still apply even if OAuth scopes are present. Argument-dependent
    // checks fail closed during discovery; hosts should separate coarse metadata policy from data
    // checks.
    try {
      checkMethodGuards(policy, principal, new Object[policy.method().getParameterCount()]);
      return true;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private boolean coarse(Policy policy, BridgePrincipal principal) {
    return policy.enabled()
        && principal.identity().scopes().containsAll(policy.scopes())
        && principal.identity().authorities().containsAll(policy.authorities());
  }

  /** Fail startup when a security configuration names an unpublished or generated wrapper key. */
  public void validateConfiguredPolicies() {
    Set<String> unknown = new TreeSet<>(properties.getTools().keySet());
    unknown.removeAll(registeredPolicies);
    if (!unknown.isEmpty())
      throw new IllegalStateException(
          "Unknown MCP policy configuration keys: "
              + unknown
              + ". Configure the original capability name, not generated prepare/submit wrappers.");
  }

  private void registerPolicy(String name) {
    if (name == null || name.isBlank() || !registeredPolicies.add(name))
      throw new IllegalStateException("Invalid or duplicate MCP policy name: " + name);
  }

  private List<MethodInterceptor> methodGuards(
      String name, Object bean, Method method, Class<?> target) {
    for (Class<? extends Annotation> unsupported :
        List.of(PreFilter.class, PostFilter.class, PostAuthorize.class))
      if (SecurityAnnotationScanners.requireUnique(unsupported).scan(method, target) != null)
        throw new IllegalStateException(
            "MCP cannot safely replay snapshots or receipts protected by @"
                + unsupported.getSimpleName()
                + ": "
                + name
                + ". Use a dedicated capability with repeatable pre-invocation authorization.");
    List<MethodInterceptor> guards = new ArrayList<>();
    activeGuard(
        name,
        bean,
        method,
        target,
        "preAuthorizeAuthorizationMethodInterceptor",
        List.of(PreAuthorize.class),
        guards);
    activeGuard(
        name,
        bean,
        method,
        target,
        "securedAuthorizationMethodInterceptor",
        List.of(Secured.class),
        guards);
    activeGuard(
        name,
        bean,
        method,
        target,
        "jsr250AuthorizationMethodInterceptor",
        List.of(
            jakarta.annotation.security.RolesAllowed.class,
            jakarta.annotation.security.DenyAll.class,
            jakarta.annotation.security.PermitAll.class),
        guards);
    return List.copyOf(guards);
  }

  private void activeGuard(
      String name,
      Object bean,
      Method method,
      Class<?> target,
      String advisorName,
      List<Class<? extends Annotation>> annotations,
      List<MethodInterceptor> guards) {
    if (SecurityAnnotationScanners.requireUnique(annotations).scan(method, target) == null) return;
    Object actual = application.containsBean(advisorName) ? application.getBean(advisorName) : null;
    if (!(bean instanceof Advised advised)
        || !(actual instanceof org.springframework.aop.PointcutAdvisor expected)
        || !(expected.getAdvice() instanceof MethodInterceptor interceptor)
        || !expected.getPointcut().getMethodMatcher().matches(method, target)
        || Arrays.stream(advised.getAdvisors())
            .noneMatch(a -> a == expected || a.getAdvice() == expected.getAdvice()))
      throw new IllegalStateException(
          "MCP method security is declared but not active on the Spring proxy: "
              + name
              + ". Enable the declared method security before exporting it.");
    guards.add(interceptor);
  }

  private void checkMethodGuards(Policy policy, BridgePrincipal principal, Object[] values) {
    var previous = SecurityContextHolder.getContext();
    var current = SecurityContextHolder.createEmptyContext();
    current.setAuthentication(principal.authentication());
    SecurityContextHolder.setContext(current);
    try {
      for (MethodInterceptor guard : beforeGuards.getOrDefault(policy.name(), List.of())) {
        Object decision =
            guard.invoke(new InspectionInvocation(policy.bean(), policy.method(), values));
        if (decision != AUTHORIZED)
          throw new org.springframework.security.access.AccessDeniedException("Denied");
      }
    } catch (RuntimeException denied) {
      throw denied;
    } catch (Throwable denied) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Method authorization could not be established", denied);
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  public record Policy(
      String name,
      Effect effect,
      boolean enabled,
      Set<String> scopes,
      Set<String> authorities,
      Object bean,
      Method method,
      PreAuthorize preAuthorize,
      boolean explicitAuthenticated) {}

  private record InspectionInvocation(Object target, Method method, Object[] arguments)
      implements MethodInvocation {
    public Method getMethod() {
      return method;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public Object proceed() {
      return AUTHORIZED;
    }

    public Object getThis() {
      return target;
    }

    public AccessibleObject getStaticPart() {
      return method;
    }
  }
}
