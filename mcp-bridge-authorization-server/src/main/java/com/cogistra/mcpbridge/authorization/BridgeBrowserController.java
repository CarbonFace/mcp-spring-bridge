package com.cogistra.mcpbridge.authorization;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

@RestController
final class BridgeBrowserController {
  private final BridgeAuthorizationProperties properties;
  private final BridgeAuthorizationService service;
  private final RegisteredClientRepository clients;
  private final HostAccountDirectory accounts;
  private final TemplateEngine templates;

  BridgeBrowserController(
      BridgeAuthorizationProperties p,
      BridgeAuthorizationService service,
      RegisteredClientRepository clients,
      HostAccountDirectory accounts) {
    this.properties = p;
    this.service = service;
    this.clients = clients;
    this.accounts = accounts;
    var resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("mcp-bridge-pages/");
    resolver.setSuffix(".html");
    resolver.setCharacterEncoding("UTF-8");
    resolver.setTemplateMode("HTML");
    resolver.setCacheable(true);
    templates = new TemplateEngine();
    templates.setTemplateResolver(resolver);
  }

  @GetMapping(
      value = BridgeAuthorizationProperties.PREFIX + "/login",
      produces = MediaType.TEXT_HTML_VALUE)
  String login(HttpServletRequest request) {
    String message =
        request.getParameterMap().containsKey("error")
            ? "The account or password could not be verified."
            : request.getParameterMap().containsKey("restart")
                ? "Your login expired. Sign in again, then restart the connection from your client."
                : request.getParameterMap().containsKey("expired")
                    ? "Your login expired. Sign in again to review and approve this connection."
                    : request.getParameterMap().containsKey("logout") ? "You have signed out." : "";
    return page("login", request, Map.of("message", message));
  }

  @GetMapping(
      value = BridgeAuthorizationProperties.PREFIX + "/consent",
      produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> consent(
      HttpServletRequest request,
      Authentication auth,
      @RequestParam String client_id,
      @RequestParam String state) {
    var pending = service.pendingRequest(auth.getName(), client_id, state);
    var client = clients.findByClientId(client_id);
    if (pending == null || client == null)
      return ResponseEntity.badRequest()
          .body(
              page(
                  "error",
                  request,
                  Map.of(
                      "message",
                      "This authorization request expired or is invalid. Restart the connection from your client.")));
    String display =
        accounts.findBySubject(auth.getName()).map(HostAccount::displayName).orElse(auth.getName());
    return ResponseEntity.ok(
        page(
            "consent",
            request,
            Map.of(
                "client",
                client.getClientName(),
                "clientId",
                client_id,
                "state",
                state,
                "scopes",
                pending.getScopes(),
                "displayName",
                display,
                "resource",
                properties.getResource())));
  }

  @GetMapping(
      value = BridgeAuthorizationProperties.PREFIX + "/grants",
      produces = MediaType.TEXT_HTML_VALUE)
  String grants(
      HttpServletRequest request, Authentication auth, @RequestParam(defaultValue = "0") int page) {
    int current = Math.max(0, page);
    var all = service.grants(auth.getName());
    var grants =
        all.stream()
            .skip(current * 25L)
            .limit(25)
            .map(
                a -> {
                  var client = clients.findById(a.getRegisteredClientId());
                  return Map.of(
                      "id",
                      a.getId(),
                      "client",
                      client == null ? a.getRegisteredClientId() : client.getClientName(),
                      "scopes",
                      String.join(" ", a.getAuthorizedScopes()),
                      "expires",
                      a.getAttribute(BridgeAuthorizationService.ABSOLUTE).toString());
                })
            .toList();
    return page(
        "grants",
        request,
        Map.of("grants", grants, "page", current, "hasNext", all.size() > (current + 1L) * 25));
  }

  @PostMapping(BridgeAuthorizationProperties.PREFIX + "/grants/revoke")
  ResponseEntity<Void> revoke(
      HttpServletRequest request, Authentication auth, @RequestParam String id) {
    if (!service.revokeForSubject(auth.getName(), id)) return ResponseEntity.notFound().build();
    return ResponseEntity.status(303)
        .header(
            HttpHeaders.LOCATION,
            request.getContextPath() + BridgeAuthorizationProperties.PREFIX + "/grants")
        .build();
  }

  private String page(String name, HttpServletRequest request, Map<String, ?> values) {
    var context = new Context(Locale.ENGLISH);
    context.setVariable("brand", properties.getBrand());
    context.setVariable("base", request.getContextPath() + BridgeAuthorizationProperties.PREFIX);
    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (csrf != null) {
      context.setVariable("csrfName", csrf.getParameterName());
      context.setVariable("csrf", csrf.getToken());
    }
    values.forEach(context::setVariable);
    return templates.process(name, context);
  }
}
