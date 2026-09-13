package com.cogistra.mcpbridge.boot;

import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.audit.*;
import com.cogistra.mcpbridge.binding.BridgeSchemas;
import com.cogistra.mcpbridge.operation.*;
import com.cogistra.mcpbridge.registry.*;
import com.cogistra.mcpbridge.security.*;
import com.cogistra.mcpbridge.transport.BridgeRequestGuard;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.function.*;

@AutoConfiguration(after = {JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    prefix = "mcp.bridge",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(BridgeProperties.class)
public class BridgeAutoConfiguration {
  @Bean
  static org.springframework.beans.factory.config.BeanPostProcessor bridgeAuthorizationDefaults(
      org.springframework.core.env.Environment environment) {
    return new org.springframework.beans.factory.config.BeanPostProcessor() {
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof BridgeProperties p
            && environment.getProperty("mcp.bridge.authorization.enabled", Boolean.class, false)) {
          String resource = environment.getProperty("mcp.bridge.authorization.resource"),
              issuer = environment.getProperty("mcp.bridge.authorization.issuer");
          if (resource != null) {
            if (p.getResource() == null) p.setResource(resource);
            else if (!p.getResource().equals(resource))
              throw new IllegalArgumentException(
                  "Bridge resource differs from authorization resource");
          }
          if (issuer != null) {
            if (p.getAuthorizationServers().isEmpty()) p.setAuthorizationServers(List.of(issuer));
            else if (!p.getAuthorizationServers().equals(List.of(issuer)))
              throw new IllegalArgumentException(
                  "Bridge authorization-servers differs from the enabled local issuer");
          }
        }
        return bean;
      }
    };
  }

  @Bean
  BridgeJson bridgeJson(ObjectProvider<ObjectMapper> mapper) {
    return new BridgeJson(
        mapper
            .getIfAvailable(() -> new ObjectMapper().findAndRegisterModules())
            .copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
  }

  @Bean
  BridgeSchemas bridgeSchemas(BridgeJson json) {
    return new BridgeSchemas(json.mapper());
  }

  @Bean
  @ConditionalOnMissingBean(BridgeIdentityResolver.class)
  BridgeIdentityResolver bridgeIdentityResolver(BridgeProperties properties) {
    validate(properties);
    return new JwtBridgeIdentityResolver(properties.getResource());
  }

  @Bean
  BridgeInvocationContext bridgeInvocationContext(BridgeIdentityResolver resolver) {
    return new BridgeInvocationContext(resolver);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(OperationStore.class)
  OperationStore bridgeOperationStore(BridgeProperties p) {
    return new LocalFileOperationStore(
        p.getStorage().resolve("operations"), p.getMaxRecords(), p.getMaxStorageBytes());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(AuditRepository.class)
  AuditRepository bridgeAuditRepository(BridgeProperties p) {
    return new LocalFileAuditRepository(
        p.getStorage().resolve("audit"), p.getMaxRecords(), p.getMaxStorageBytes());
  }

  @Bean
  OperationCoordinator bridgeOperationCoordinator(OperationStore store, BridgeProperties p) {
    return new OperationCoordinator(
        store,
        Clock.systemUTC(),
        p.getConfirmationTtl(),
        p.getMaxArgumentBytes(),
        Math.max(1, p.getMaxResultBytes() / 2));
  }

  @Bean
  BridgePolicyGate bridgePolicyGate(
      BridgeProperties p,
      ObjectProvider<BridgeAuthorization> policies,
      ApplicationContext context) {
    return new BridgePolicyGate(p, policies.orderedStream().toList(), context);
  }

  @Bean
  BridgeToolCatalog bridgeToolCatalog(
      BridgeJson json,
      BridgeSchemas schemas,
      BridgePolicyGate gate,
      BridgeInvocationContext identities,
      OperationCoordinator operations,
      AuditRepository audit,
      BridgeProperties properties) {
    return new BridgeToolCatalog(
        json.protocolMapper(), schemas, gate, identities, operations, audit, properties);
  }

  @Bean
  WebMvcStatelessServerTransport bridgeTransport(
      BridgeJson json, BridgeProperties p, BridgeInvocationContext identities) {
    validate(p);
    return WebMvcStatelessServerTransport.builder()
        .jsonMapper(new JacksonMcpJsonMapper(json.protocolMapper()))
        .messageEndpoint(p.getPath())
        .contextExtractor(request -> identities.capture())
        .build();
  }

  @Bean
  org.springframework.web.servlet.function.support.RouterFunctionMapping
      bridgeRouterFunctionMapping(
          WebMvcStatelessServerTransport transport, BridgeProperties p, BridgeJson json) {
    var mapping =
        new org.springframework.web.servlet.function.support.RouterFunctionMapping(
            bridgeRoutes(transport, p));
    mapping.setOrder(-1);
    mapping.setMessageConverters(
        List.of(
            new org.springframework.http.converter.StringHttpMessageConverter(
                java.nio.charset.StandardCharsets.UTF_8),
            new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                json.protocolMapper())));
    return mapping;
  }

  private RouterFunction<ServerResponse> bridgeRoutes(
      WebMvcStatelessServerTransport transport, BridgeProperties p) {
    if (!p.isPublishResourceMetadata()) return transport.getRouterFunction();
    Map<String, Object> metadata =
        Map.of(
            "resource",
            p.getResource(),
            "authorization_servers",
            p.getAuthorizationServers(),
            "bearer_methods_supported",
            List.of("header"));
    String path = URI.create(p.getResource()).getPath();
    RouterFunction<ServerResponse> discovery =
        RouterFunctions.route()
            .GET(
                "/.well-known/oauth-protected-resource",
                request -> ServerResponse.ok().header("Cache-Control", "no-store").body(metadata))
            .build();
    if (path != null && !path.isBlank() && !path.equals("/"))
      discovery =
          discovery.and(
              RouterFunctions.route()
                  .GET(
                      "/.well-known/oauth-protected-resource" + path,
                      request ->
                          ServerResponse.ok().header("Cache-Control", "no-store").body(metadata))
                  .build());
    return discovery.and(transport.getRouterFunction());
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 50)
  @ConditionalOnMissingBean(name = "bridgeSecurityFilterChain")
  SecurityFilterChain bridgeSecurityFilterChain(
      HttpSecurity http, BridgeProperties p, @Qualifier("bridgeJwtDecoder") JwtDecoder decoder)
      throws Exception {
    String metadata =
        URI.create(p.getResource())
            .resolve(
                "/.well-known/oauth-protected-resource" + URI.create(p.getResource()).getPath())
            .toString();
    http.securityMatcher(
            p.getPath(),
            p.getPath() + "/**",
            "/mcp-bridge/files/**",
            "/mcp-bridge/audit/**",
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/.well-known/oauth-protected-resource",
                        "/.well-known/oauth-protected-resource/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.decoder(decoder))
                    .authenticationEntryPoint(
                        (request, response, error) -> {
                          response.setStatus(401);
                          response.setHeader(
                              "WWW-Authenticate", "Bearer resource_metadata=\"" + metadata + "\"");
                          response.setHeader("Cache-Control", "no-store");
                        }))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) -> {
                          response.setStatus(401);
                          response.setHeader(
                              "WWW-Authenticate", "Bearer resource_metadata=\"" + metadata + "\"");
                        })
                    .accessDeniedHandler((request, response, error) -> response.sendError(403)))
        .addFilterBefore(new BridgeRequestGuard(p), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  BridgeAuditController bridgeAuditController(
      AuditRepository audit, BridgeInvocationContext identities) {
    return new BridgeAuditController(audit, identities);
  }

  @Bean(destroyMethod = "close")
  BridgeRuntime bridgeRuntime(
      ConfigurableApplicationContext context,
      BridgeJson json,
      BridgeProperties properties,
      BridgeToolCatalog tools,
      BridgePolicyGate gate,
      BridgeInvocationContext identities,
      WebMvcStatelessServerTransport transport,
      BridgeSchemas schemas,
      AuditRepository audit) {
    return new BridgeRuntime(
        context, json, properties, tools, gate, identities, transport, schemas, audit);
  }

  private static void validate(BridgeProperties p) {
    if (p.getPath() == null
        || !p.getPath().matches("/[A-Za-z0-9_/-]+")
        || p.getPath().endsWith("/")
        || p.getPath().contains("//"))
      throw new IllegalArgumentException(
          "mcp.bridge.path must be one non-root absolute path without wildcards");
    if (p.getResource() == null || p.getAuthorizationServers().isEmpty())
      throw new IllegalArgumentException(
          "Configure mcp.bridge.resource and mcp.bridge.authorization-servers");
    uri(p.getResource());
    p.getAuthorizationServers().forEach(BridgeAutoConfiguration::uri);
    if (p.getMaxArgumentBytes() < 1
        || p.getMaxArgumentBytes() > 16 * 1024 * 1024
        || p.getMaxResultBytes() < 1
        || p.getMaxResultBytes() > 16 * 1024 * 1024
        || p.getMaxExportBytes() < 1
        || p.getMaxExportBytes() > 1024L * 1024 * 1024)
      throw new IllegalArgumentException("Invalid bridge payload limit");
    for (String origin : p.getAllowedOrigins()) {
      URI value = uri(origin);
      if (!origin.equals(value.getScheme() + "://" + value.getRawAuthority()))
        throw new IllegalArgumentException(
            "Allowed origins must contain only scheme and authority");
    }
  }

  private static URI uri(String text) {
    URI uri = URI.create(text);
    boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
    if (uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getFragment() != null
        || uri.getQuery() != null
        || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())))
      throw new IllegalArgumentException(
          "Bridge public URLs require HTTPS (HTTP is allowed only on loopback)");
    return uri;
  }
}
