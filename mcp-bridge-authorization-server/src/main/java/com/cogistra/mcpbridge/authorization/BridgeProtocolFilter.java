package com.cogistra.mcpbridge.authorization;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/** Serializes state-changing protocol exchanges and applies limits before form conversion. */
final class BridgeProtocolFilter extends OncePerRequestFilter {
  private final BridgeAuthorizationService service;
  private final BridgeAuthorizationProperties properties;
  private final Map<String, Deque<Instant>> attempts = new HashMap<>();
  private final Clock clock;
  private final HostAccountDirectory accounts;

  BridgeProtocolFilter(
      BridgeAuthorizationService service,
      BridgeAuthorizationProperties properties,
      Clock clock,
      HostAccountDirectory accounts) {
    this.service = service;
    this.properties = properties;
    this.clock = clock;
    this.accounts = accounts;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = BridgeBrowserSupport.path(request);
    boolean post = "POST".equals(request.getMethod());
    if (post && request.getContentLengthLong() > 16384) {
      error(response, 413, "invalid_request");
      return;
    }
    if (post
        && (path.endsWith("/token") || path.endsWith("/revoke") || path.endsWith("/authorize"))) {
      if (request.getContentType() == null
          || !request
              .getContentType()
              .toLowerCase(Locale.ROOT)
              .startsWith("application/x-www-form-urlencoded")
          || request.getQueryString() != null) {
        error(response, 400, "invalid_request");
        return;
      }
      for (var parameter : request.getParameterMap().entrySet()) {
        if (parameter.getValue().length != 1
            && !(path.endsWith("/authorize") && parameter.getKey().equals("scope"))) {
          error(response, 400, "invalid_request");
          return;
        }
        if (parameter.getKey().length() > 100
            || Arrays.stream(parameter.getValue()).anyMatch(v -> v.length() > 8192)) {
          error(response, 400, "invalid_request");
          return;
        }
      }
      String resource = request.getParameter("resource");
      if (resource != null && !properties.getResource().equals(resource)) {
        error(response, 400, "invalid_target");
        return;
      }
    }
    if (post && path.endsWith("/authorize")) {
      var repository = new HttpSessionCsrfTokenRepository();
      repository.setSessionAttributeName(BridgeBrowserSupport.CSRF);
      CsrfToken token = repository.loadToken(request);
      String actual =
          token == null
              ? null
              : new XorCsrfTokenRequestAttributeHandler().resolveCsrfTokenValue(request, token);
      if (token == null
          || actual == null
          || !MessageDigest.isEqual(
              token.getToken().getBytes(StandardCharsets.UTF_8),
              actual.getBytes(StandardCharsets.UTF_8))) {
        error(response, 403, "access_denied");
        return;
      }
    }
    if (post && path.equals(BridgeAuthorizationProperties.PREFIX + "/login")) {
      String username = accounts.normalizeUsername(request.getParameter("username"));
      if (!permit("ip:" + request.getRemoteAddr(), 120)
          || !permit("account:" + BridgeAuthorizationService.hash(username), 10)) {
        response.setHeader("Retry-After", "600");
        response.setStatus(429);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Too many login attempts. Try again in 10 minutes.");
        return;
      }
    }
    boolean protocol =
        path.equals(BridgeAuthorizationProperties.PREFIX + "/authorize")
            || path.equals(BridgeAuthorizationProperties.PREFIX + "/token")
            || path.equals(BridgeAuthorizationProperties.PREFIX + "/revoke");
    if (!protocol) {
      chain.doFilter(request, response);
      return;
    }
    try {
      service
          .store()
          .locked(
              () -> {
                try {
                  chain.doFilter(request, response);
                  return null;
                } catch (IOException | ServletException e) {
                  throw new FilterFailure(e);
                }
              });
    } catch (FilterFailure e) {
      if (e.getCause() instanceof IOException io) throw io;
      throw (ServletException) e.getCause();
    } catch (RuntimeException failure) {
      if (!response.isCommitted()) {
        response.resetBuffer();
        error(response, 500, "server_error");
      } else throw failure;
    }
  }

  private synchronized boolean permit(String key, int limit) {
    Instant cutoff = clock.instant().minus(Duration.ofMinutes(10));
    attempts
        .entrySet()
        .removeIf(e -> e.getValue().isEmpty() || e.getValue().getLast().isBefore(cutoff));
    if (attempts.size() >= 10000 && !attempts.containsKey(key)) return false;
    var q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
    while (!q.isEmpty() && !q.getFirst().isAfter(cutoff)) q.removeFirst();
    if (q.size() >= limit) return false;
    q.addLast(clock.instant());
    return true;
  }

  private static void error(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"" + code + "\"}");
  }

  private static final class FilterFailure extends RuntimeException {
    FilterFailure(Exception cause) {
      super(cause);
    }
  }
}
