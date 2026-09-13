package com.cogistra.mcpbridge.security;

import com.cogistra.mcpbridge.api.*;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;

/** Carries only server-verified credentials across the SDK scheduler boundary. */
public final class BridgeInvocationContext {
  private static final String KEY = BridgeInvocationContext.class.getName();
  private final BridgeIdentityResolver resolver;

  public BridgeInvocationContext(BridgeIdentityResolver resolver) {
    this.resolver = resolver;
  }

  public McpTransportContext capture() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    requireAuthenticated(authentication);
    resolver.resolve(authentication);
    return McpTransportContext.create(Map.of(KEY, authentication));
  }

  public BridgePrincipal resolve(McpTransportContext context) {
    Object value = context.get(KEY);
    if (!(value instanceof Authentication authentication))
      throw new BridgeException("UNAUTHENTICATED", "Authentication is required");
    requireAuthenticated(authentication);
    BridgePrincipal principal = resolver.resolve(authentication);
    requireAuthenticated(principal.authentication());
    return principal;
  }

  public BridgePrincipal current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    requireAuthenticated(auth);
    return resolver.resolve(auth);
  }

  public <T> T call(McpTransportContext context, Work<T> work) throws Exception {
    BridgePrincipal principal = resolve(context);
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext current = SecurityContextHolder.createEmptyContext();
    current.setAuthentication(principal.authentication());
    SecurityContextHolder.setContext(current);
    try {
      return work.run(principal);
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  public static void requireAuthenticated(Authentication auth) {
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
      throw new BridgeException("UNAUTHENTICATED", "Authentication is required");
  }

  @FunctionalInterface
  public interface Work<T> {
    T run(BridgePrincipal principal) throws Exception;
  }
}
