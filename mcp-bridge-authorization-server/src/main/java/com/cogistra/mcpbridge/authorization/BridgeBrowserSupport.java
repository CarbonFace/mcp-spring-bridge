package com.cogistra.mcpbridge.authorization;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

final class BridgeBrowserSupport {
  static final String RESTART = BridgeBrowserSupport.class.getName() + ".restart";
  static final String CONTEXT = "MCP_BRIDGE_SECURITY_CONTEXT",
      SAVED = "MCP_BRIDGE_SAVED_REQUEST",
      CSRF = "MCP_BRIDGE_CSRF";

  static HttpSessionRequestCache requestCache() {
    var cache = new HttpSessionRequestCache();
    cache.setSessionAttrName(SAVED);
    cache.setRequestMatcher(
        r ->
            "GET".equals(r.getMethod())
                && path(r).equals(BridgeAuthorizationProperties.PREFIX + "/authorize"));
    cache.setMatchingRequestParameterName(null);
    return cache;
  }

  static String path(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static String restart(OAuth2AuthorizationRequest request) {
    var params = new LinkedHashMap<String, String>();
    params.put("response_type", "code");
    params.put("client_id", request.getClientId());
    params.put("redirect_uri", request.getRedirectUri());
    params.put("scope", String.join(" ", request.getScopes()));
    if (request.getState() != null) params.put("state", request.getState());
    for (String key : List.of("resource", "code_challenge", "code_challenge_method")) {
      Object value = request.getAdditionalParameters().get(key);
      if (value instanceof String s) params.put(key, s);
    }
    return params.entrySet().stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .reduce((a, b) -> a + "&" + b)
        .orElse("");
  }

  static boolean current(
      Authentication a,
      HostAccountDirectory accounts,
      BridgeAuthorizationProperties p,
      Clock clock) {
    if (!(a instanceof UsernamePasswordAuthenticationToken)
        || !a.isAuthenticated()
        || !(a.getDetails() instanceof Map<?, ?> details)) return false;
    try {
      Instant login =
          Instant.parse(
              Objects.toString(details.get(BridgePasswordAuthenticationProvider.LOGIN), ""));
      if (!clock.instant().isBefore(login.plus(p.getBrowserSessionTtl()))) return false;
      return accounts
          .findBySubject(a.getName())
          .filter(HostAccount::enabled)
          .filter(
              account ->
                  account
                      .version()
                      .equals(details.get(BridgePasswordAuthenticationProvider.VERSION)))
          .isPresent();
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  static final class SessionFilter extends OncePerRequestFilter {
    private final HostAccountDirectory accounts;
    private final BridgeAuthorizationService service;
    private final BridgeAuthorizationProperties properties;
    private final Clock clock;

    SessionFilter(
        HostAccountDirectory accounts,
        BridgeAuthorizationService service,
        BridgeAuthorizationProperties properties,
        Clock clock) {
      this.accounts = accounts;
      this.service = service;
      this.properties = properties;
      this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      Authentication a = SecurityContextHolder.getContext().getAuthentication();
      String path = path(request);
      boolean browser =
          path.equals(BridgeAuthorizationProperties.PREFIX + "/authorize")
              || path.equals(BridgeAuthorizationProperties.PREFIX + "/consent")
              || path.equals(BridgeAuthorizationProperties.PREFIX + "/grants")
              || path.equals(BridgeAuthorizationProperties.PREFIX + "/grants/revoke");
      if (browser && a != null && a.isAuthenticated() && !current(a, accounts, properties, clock)) {
        OAuth2AuthorizationRequest pending = null;
        boolean consent =
            path.endsWith("/consent")
                || (path.endsWith("/authorize") && "POST".equals(request.getMethod()));
        if (consent
            && request.getParameterValues("client_id") != null
            && request.getParameterValues("client_id").length == 1
            && request.getParameterValues("state") != null
            && request.getParameterValues("state").length == 1)
          pending =
              service.pendingRequest(
                  a.getName(), request.getParameter("client_id"), request.getParameter("state"));
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
          request.getSession(false).removeAttribute(CONTEXT);
          request.getSession(false).removeAttribute(SAVED);
          request.getSession(false).removeAttribute(RESTART);
          request.getSession(false).removeAttribute(CSRF);
        }
        if (pending != null) request.getSession(true).setAttribute(RESTART, restart(pending));
        else requestCache().saveRequest(request, response);
        response.sendRedirect(
            request.getContextPath()
                + BridgeAuthorizationProperties.PREFIX
                + "/login?expired"
                + (consent && pending == null ? "&restart" : ""));
        return;
      }
      chain.doFilter(request, response);
    }
  }
}
