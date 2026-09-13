package com.cogistra.mcpbridge.authorization;

import com.cogistra.mcpbridge.api.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.util.matcher.*;

@AutoConfiguration(beforeName = "com.cogistra.mcpbridge.boot.BridgeAutoConfiguration")
@ConditionalOnProperty(prefix = "mcp.bridge.authorization", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BridgeAuthorizationProperties.class)
public class BridgeAuthorizationAutoConfiguration {
  @Bean("bridgeAuthorizationClock")
  @ConditionalOnMissingBean(name = "bridgeAuthorizationClock")
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean("bridgeAuthorizationClients")
  RegisteredClientRepository clients(BridgeAuthorizationProperties p) {
    p.validate();
    var list =
        p.getClients().stream()
            .map(
                c ->
                    RegisteredClient.withId(c.getId())
                        .clientId(c.getId())
                        .clientName(c.getName())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                        .redirectUris(uris -> uris.addAll(c.getRedirectUris()))
                        .scopes(scopes -> scopes.addAll(c.getScopes()))
                        .clientSettings(
                            ClientSettings.builder()
                                .requireProofKey(true)
                                .requireAuthorizationConsent(true)
                                .build())
                        .tokenSettings(
                            TokenSettings.builder()
                                .accessTokenTimeToLive(p.getAccessTokenTtl())
                                .refreshTokenTimeToLive(p.getRefreshTokenTtl())
                                .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                                .reuseRefreshTokens(false)
                                .build())
                        .build())
            .toList();
    return new InMemoryRegisteredClientRepository(
        list); // Configuration is the durable source of client registrations.
  }

  @Bean
  @ConditionalOnMissingBean(BridgeAuthorizationStore.class)
  FileAuthorizationStore bridgeAuthorizationStore(
      BridgeAuthorizationProperties p,
      @Qualifier("bridgeAuthorizationClients") RegisteredClientRepository clients) {
    return new FileAuthorizationStore(
        p.getStorageDirectory(),
        Base64.getDecoder().decode(externalFile(p.getStorageKey(), 1024).trim()),
        clients);
  }

  @Bean
  BridgeAuthorizationService bridgeAuthorizationService(
      BridgeAuthorizationStore store,
      HostAccountDirectory accounts,
      BridgeAuthorizationProperties p,
      @Qualifier("bridgeAuthorizationClock") Clock clock) {
    return new BridgeAuthorizationService(store, accounts, p, clock);
  }

  @Bean("bridgeAuthorizationRsaKey")
  RSAKey signingKey(BridgeAuthorizationProperties p) throws Exception {
    RSAKey key = RSAKey.parse(externalFile(p.getSigningJwk(), 16384));
    if (!key.isPrivate() || key.size() < 2048 || key.getKeyID() == null || key.getKeyID().isBlank())
      throw new IllegalArgumentException(
          "A persistent private RSA JWK of at least 2048 bits with kid is required");
    return key;
  }

  @Bean("bridgeAuthorizationJwkSource")
  JWKSource<SecurityContext> jwks(@Qualifier("bridgeAuthorizationRsaKey") RSAKey key) {
    JWKSet set = new JWKSet(key);
    return (selector, context) -> selector.select(set);
  }

  @Bean("bridgeJwtDecoder")
  @ConditionalOnMissingBean(name = "bridgeJwtDecoder")
  JwtDecoder decoder(
      @Qualifier("bridgeAuthorizationRsaKey") RSAKey key,
      BridgeAuthorizationProperties p,
      BridgeAuthorizationService service)
      throws Exception {
    var decoder =
        NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(p.getIssuer()),
            jwt -> {
              try {
                if (!jwt.getAudience().equals(List.of(p.getResource()))
                    || jwt.getSubject() == null
                    || jwt.getClaimAsString("client_id") == null)
                  throw new IllegalArgumentException();
                var account = service.currentAccountForAccess(jwt.getTokenValue());
                if (!account.subject().equals(jwt.getSubject()))
                  throw new IllegalArgumentException();
                return OAuth2TokenValidatorResult.success();
              } catch (RuntimeException failure) {
                return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Inactive authorization", null));
              }
            }));
    return decoder;
  }

  @Bean
  @ConditionalOnMissingBean(BridgeIdentityResolver.class)
  BridgeIdentityResolver bridgeIdentityResolver(
      @Qualifier("bridgeJwtDecoder") JwtDecoder decoder,
      BridgeAuthorizationProperties p,
      BridgeAuthorizationService service) {
    return authentication -> {
      if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
          || !authentication.isAuthenticated())
        throw new BadCredentialsException("A verified bridge bearer token is required");
      Jwt jwt = decoder.decode(jwtAuthentication.getToken().getTokenValue());
      HostAccount account;
      try {
        account = service.currentAccountForAccess(jwt.getTokenValue());
      } catch (RuntimeException failure) {
        throw new BadCredentialsException("Inactive authorization");
      }
      var scopes = jwt.getClaimAsStringList("scope");
      var identity =
          new BridgeIdentity(
              p.getIssuer(),
              account.subject(),
              jwt.getClaimAsString("client_id"),
              scopes == null ? Set.of() : Set.copyOf(scopes),
              account.authorities());
      var current =
          new JwtAuthenticationToken(
              jwt,
              account.authorities().stream().map(SimpleGrantedAuthority::new).toList(),
              account.subject());
      return new BridgePrincipal(identity, current);
    };
  }

  @Bean("bridgeAuthorizationTokenGenerator")
  OAuth2TokenGenerator<?> tokenGenerator(
      @Qualifier("bridgeAuthorizationJwkSource") JWKSource<SecurityContext> jwks,
      BridgeAuthorizationService service,
      BridgeAuthorizationProperties p,
      @Qualifier("bridgeAuthorizationClock") Clock clock) {
    var jwt = new JwtGenerator(new NimbusJwtEncoder(jwks));
    jwt.setJwtCustomizer(
        context -> {
          Instant absolute = service.absoluteExpiry(context.getAuthorization());
          Instant expiry = clock.instant().plus(p.getAccessTokenTtl());
          if (expiry.isAfter(absolute)) expiry = absolute;
          context
              .getClaims()
              .audience(List.of(p.getResource()))
              .claim("client_id", context.getRegisteredClient().getClientId())
              .claim("scope", new ArrayList<>(context.getAuthorizedScopes()))
              .expiresAt(expiry);
        });
    OAuth2TokenGenerator<OAuth2RefreshToken> refresh =
        context -> {
          if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
          if (!context
              .getRegisteredClient()
              .getClientAuthenticationMethods()
              .equals(Set.of(ClientAuthenticationMethod.NONE))) return null;
          Instant now = clock.instant(),
              expiry = now.plus(p.getRefreshTokenTtl()),
              absolute = service.absoluteExpiry(context.getAuthorization());
          if (expiry.isAfter(absolute)) expiry = absolute;
          if (!expiry.isAfter(now))
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
          byte[] random = new byte[64];
          new java.security.SecureRandom().nextBytes(random);
          return new OAuth2RefreshToken(
              Base64.getUrlEncoder().withoutPadding().encodeToString(random), now, expiry);
        };
    return new DelegatingOAuth2TokenGenerator(jwt, refresh);
  }

  @Bean
  @Order(-210)
  SecurityFilterChain bridgeAuthorizationProtocolChain(
      HttpSecurity http,
      BridgeAuthorizationProperties p,
      BridgeAuthorizationService service,
      HostAccountDirectory accounts,
      @Qualifier("bridgeAuthorizationClients") RegisteredClientRepository clients,
      @Qualifier("bridgeAuthorizationTokenGenerator") OAuth2TokenGenerator<?> generator,
      @Qualifier("bridgeAuthorizationJwkSource") JWKSource<SecurityContext> jwks,
      @Qualifier("bridgeAuthorizationClock") Clock clock)
      throws Exception {
    String base = BridgeAuthorizationProperties.PREFIX;
    var settings =
        AuthorizationServerSettings.builder()
            .issuer(p.getIssuer())
            .authorizationEndpoint(base + "/authorize")
            .tokenEndpoint(base + "/token")
            .tokenRevocationEndpoint(base + "/revoke")
            .jwkSetEndpoint(base + "/jwks")
            .tokenIntrospectionEndpoint(base + "/unsupported/introspect")
            .deviceAuthorizationEndpoint(base + "/unsupported/device")
            .deviceVerificationEndpoint(base + "/unsupported/verify")
            .pushedAuthorizationRequestEndpoint(base + "/unsupported/par")
            .build();
    var sas = OAuth2AuthorizationServerConfigurer.authorizationServer();
    var publicClient = new PublicClientAuthentication(clients, service, p);
    // Every connection receives fresh explicit consent; approved scopes live in its durable
    // authorization.
    OAuth2AuthorizationConsentService consent =
        new OAuth2AuthorizationConsentService() {
          public void save(OAuth2AuthorizationConsent c) {}

          public void remove(OAuth2AuthorizationConsent c) {}

          public OAuth2AuthorizationConsent findById(String client, String principal) {
            return null;
          }
        };
    http.securityMatcher(
            new OrRequestMatcher(
                match(base + "/authorize"),
                match(base + "/token"),
                match(base + "/revoke"),
                match(base + "/jwks")))
        .with(
            sas,
            server ->
                server
                    .registeredClientRepository(clients)
                    .authorizationService(service)
                    .authorizationConsentService(consent)
                    .authorizationServerSettings(settings)
                    .tokenGenerator(generator)
                    .clientAuthentication(
                        client ->
                            client
                                .authenticationConverter(publicClient)
                                .authenticationProvider(publicClient))
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint
                                .consentPage(base + "/consent")
                                .authenticationProviders(
                                    providers ->
                                        providers.forEach(
                                            provider -> {
                                              if (provider
                                                  instanceof
                                                  OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                          code) {
                                                code.setAuthorizationConsentRequired(
                                                    context -> true);
                                                code.setAuthenticationValidator(
                                                    OAuth2AuthorizationCodeRequestAuthenticationValidator
                                                        .DEFAULT_REDIRECT_URI_VALIDATOR
                                                        .andThen(
                                                            OAuth2AuthorizationCodeRequestAuthenticationValidator
                                                                .DEFAULT_SCOPE_VALIDATOR)
                                                        .andThen(
                                                            context -> {
                                                              OAuth2AuthorizationCodeRequestAuthenticationToken
                                                                  token =
                                                                      context.getAuthentication();
                                                              var extra =
                                                                  token.getAdditionalParameters();
                                                              if (!p.getResource()
                                                                  .equals(extra.get("resource")))
                                                                throw authorizationError(
                                                                    "invalid_target", token);
                                                              if (!"S256"
                                                                      .equals(
                                                                          extra.get(
                                                                              "code_challenge_method"))
                                                                  || !(extra.get("code_challenge")
                                                                      instanceof String challenge)
                                                                  || !challenge.matches(
                                                                      "[A-Za-z0-9_-]{43}"))
                                                                throw authorizationError(
                                                                    OAuth2ErrorCodes
                                                                        .INVALID_REQUEST,
                                                                    token);
                                                            }));
                                              }
                                            }))))
        .authorizeHttpRequests(
            a -> a.requestMatchers(base + "/jwks").permitAll().anyRequest().authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(base + "/login")));
    http.setSharedObject(JWKSource.class, jwks);
    common(http, p, accounts, service, clock);
    return http.build();
  }

  @Bean
  @Order(-200)
  SecurityFilterChain bridgeAuthorizationBrowserChain(
      HttpSecurity http,
      BridgeAuthorizationProperties p,
      BridgeAuthorizationService service,
      HostAccountDirectory accounts,
      @Qualifier("bridgeBrowserAuthenticationProvider")
          ObjectProvider<AuthenticationProvider> providers,
      ObjectProvider<HostPasswordVerifier> verifiers,
      @Qualifier("bridgeAuthorizationClock") Clock clock)
      throws Exception {
    String base = BridgeAuthorizationProperties.PREFIX;
    AuthenticationProvider provider =
        providers.getIfAvailable(
            () -> new BridgePasswordAuthenticationProvider(verifiers.getObject(), accounts, clock));
    AuthenticationManager manager =
        input -> {
          var normalized =
              UsernamePasswordAuthenticationToken.unauthenticated(
                  accounts.normalizeUsername(input.getName()), input.getCredentials());
          Authentication verified;
          try {
            verified = provider.authenticate(normalized);
          } finally {
            normalized.eraseCredentials();
          }
          if (verified == null
              || !verified.isAuthenticated()
              || !(verified.getPrincipal() instanceof HostAccount verifiedAccount))
            throw new BadCredentialsException("Account verification failed");
          var account =
              accounts
                  .findBySubject(verifiedAccount.subject())
                  .filter(HostAccount::enabled)
                  .filter(current -> current.version().equals(verifiedAccount.version()))
                  .orElseThrow(() -> new BadCredentialsException("Account verification failed"));
          var result =
              UsernamePasswordAuthenticationToken.authenticated(
                  account.subject(),
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_BRIDGE_BROWSER")));
          result.setDetails(
              Map.of(
                  BridgePasswordAuthenticationProvider.VERSION,
                  account.version(),
                  BridgePasswordAuthenticationProvider.LOGIN,
                  clock.instant().toString()));
          if (input instanceof org.springframework.security.core.CredentialsContainer credentials)
            credentials.eraseCredentials();
          return result;
        };
    http.securityMatcher(base + "/**")
        .authenticationManager(manager)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(base + "/login")
                    .permitAll()
                    .requestMatchers(
                        base + "/consent",
                        base + "/grants",
                        base + "/grants/revoke",
                        base + "/logout")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .formLogin(
            form ->
                form.loginPage(base + "/login")
                    .loginProcessingUrl(base + "/login")
                    .failureUrl(base + "/login?error")
                    .successHandler(
                        (request, response, auth) -> {
                          var session = request.getSession(false);
                          String restart =
                              session == null
                                  ? null
                                  : (String) session.getAttribute(BridgeBrowserSupport.RESTART);
                          if (session != null)
                            session.removeAttribute(BridgeBrowserSupport.RESTART);
                          var cache = BridgeBrowserSupport.requestCache();
                          var saved = cache.getRequest(request, response);
                          cache.removeRequest(request, response);
                          String query =
                              restart != null
                                  ? restart
                                  : saved == null
                                      ? null
                                      : URI.create(saved.getRedirectUrl()).getRawQuery();
                          response.sendRedirect(
                              request.getContextPath()
                                  + base
                                  + (query == null ? "/grants" : "/authorize?" + query));
                        }))
        .logout(
            logout ->
                logout
                    .logoutUrl(base + "/logout")
                    .invalidateHttpSession(false)
                    .clearAuthentication(true)
                    .addLogoutHandler(
                        (request, response, auth) -> {
                          var s = request.getSession(false);
                          if (s != null) {
                            s.removeAttribute(BridgeBrowserSupport.CONTEXT);
                            s.removeAttribute(BridgeBrowserSupport.SAVED);
                            s.removeAttribute(BridgeBrowserSupport.RESTART);
                          }
                        })
                    .logoutSuccessUrl(base + "/login?logout"))
        .sessionManagement(
            session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
    common(http, p, accounts, service, clock);
    return http.build();
  }

  @Bean
  @Order(-220)
  SecurityFilterChain bridgeDiscoveryChain(HttpSecurity http, BridgeAuthorizationProperties p)
      throws Exception {
    http.securityMatcher(
            new OrRequestMatcher(match(p.metadataPath()), match(p.resourceMetadataPath())))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .requestCache(c -> c.disable())
        .securityContext(c -> c.disable())
        .addFilterBefore(new BridgeMetadataFilter(p), SecurityContextHolderFilter.class);
    return http.build();
  }

  @Bean
  BridgeBrowserController bridgeBrowserController(
      BridgeAuthorizationProperties p,
      BridgeAuthorizationService s,
      @Qualifier("bridgeAuthorizationClients") RegisteredClientRepository clients,
      HostAccountDirectory accounts) {
    return new BridgeBrowserController(p, s, clients, accounts);
  }

  private void common(
      HttpSecurity http,
      BridgeAuthorizationProperties p,
      HostAccountDirectory accounts,
      BridgeAuthorizationService service,
      Clock clock)
      throws Exception {
    var repository = new HttpSessionSecurityContextRepository();
    repository.setSpringSecurityContextKey(BridgeBrowserSupport.CONTEXT);
    var csrf = new HttpSessionCsrfTokenRepository();
    csrf.setSessionAttributeName(BridgeBrowserSupport.CSRF);
    http.securityContext(c -> c.securityContextRepository(repository))
        .requestCache(c -> c.requestCache(BridgeBrowserSupport.requestCache()))
        .csrf(c -> c.csrfTokenRepository(csrf))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'"))
                    .referrerPolicy(
                        r ->
                            r.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
        .addFilterAfter(
            new BridgeBrowserSupport.SessionFilter(accounts, service, p, clock),
            SecurityContextHolderFilter.class)
        .addFilterAfter(new BridgeProtocolFilter(service, p, clock, accounts), CsrfFilter.class);
  }

  private static RequestMatcher match(String path) {
    return request -> BridgeBrowserSupport.path(request).equals(path);
  }

  private static OAuth2AuthorizationCodeRequestAuthenticationException authorizationError(
      String code, OAuth2AuthorizationCodeRequestAuthenticationToken token) {
    return new OAuth2AuthorizationCodeRequestAuthenticationException(new OAuth2Error(code), token);
  }

  private static String externalFile(String uri, int maxBytes) {
    try {
      URI location = URI.create(Objects.requireNonNull(uri, "External key file is required"));
      if (!"file".equals(location.getScheme()))
        throw new IllegalArgumentException("Key locations must be absolute file URIs");
      Path path = Path.of(location);
      if (!path.isAbsolute()
          || Files.isSymbolicLink(path)
          || !Files.isRegularFile(path)
          || Files.size(path) > maxBytes)
        throw new IllegalArgumentException("Invalid external key file");
      return Files.readString(path);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read external authorization key file", e);
    }
  }
}
