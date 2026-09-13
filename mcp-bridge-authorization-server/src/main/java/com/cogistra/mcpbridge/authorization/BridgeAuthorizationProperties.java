package com.cogistra.mcpbridge.authorization;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.bridge.authorization")
public class BridgeAuthorizationProperties {
  public static final String PREFIX = "/mcp-bridge/oauth";
  private boolean enabled;
  private boolean developmentLoopback;
  private String issuer;
  private String resource;
  private String brand = "MCP Connection";
  private String signingJwk;
  private Path storageDirectory;
  private String storageKey;
  private Duration accessTokenTtl = Duration.ofHours(24);
  private Duration refreshTokenTtl = Duration.ofDays(7);
  private Duration authorizationTtl = Duration.ofDays(30);
  private Duration browserSessionTtl = Duration.ofHours(8);
  private List<Client> clients = new ArrayList<>();

  public void validate() {
    absolute(issuer, developmentLoopback);
    absolute(resource, developmentLoopback);
    if (issuer.endsWith("/"))
      throw new IllegalArgumentException("Authorization issuer must not end with slash");
    if (brand == null || brand.isBlank() || brand.length() > 100)
      throw new IllegalArgumentException("Invalid authorization brand");
    for (Duration ttl :
        List.of(accessTokenTtl, refreshTokenTtl, authorizationTtl, browserSessionTtl))
      if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(366)) > 0)
        throw new IllegalArgumentException(
            "Authorization TTL must be positive and at most 366 days");
    if (accessTokenTtl.compareTo(authorizationTtl) > 0
        || refreshTokenTtl.compareTo(authorizationTtl) > 0)
      throw new IllegalArgumentException("Token TTL exceeds absolute authorization TTL");
    Set<String> ids = new HashSet<>();
    if (clients.isEmpty())
      throw new IllegalArgumentException("Configure at least one authorization client");
    for (Client c : clients) {
      if (c.id == null
          || !c.id.matches("[A-Za-z0-9._:-]{1,128}")
          || !ids.add(c.id)
          || c.name == null
          || c.name.isBlank()
          || c.redirectUris.isEmpty()
          || c.scopes.isEmpty())
        throw new IllegalArgumentException("Invalid or duplicate authorization client");
      c.redirectUris.forEach(uri -> absolute(uri, true));
      if (c.scopes.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9:._/-]{1,100}")))
        throw new IllegalArgumentException("Invalid authorization scope");
    }
  }

  private static void absolute(String value, boolean loopbackAllowed) {
    try {
      URI u = URI.create(value);
      boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(u.getHost());
      if (!u.isAbsolute()
          || u.getHost() == null
          || u.getRawUserInfo() != null
          || u.getRawFragment() != null
          || u.getRawQuery() != null
          || !("https".equals(u.getScheme())
              || (loopbackAllowed && loopback && "http".equals(u.getScheme()))))
        throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Use an absolute HTTPS URI; loopback HTTP requires explicit development mode");
    }
  }

  public String endpoint(String suffix) {
    URI u = URI.create(issuer);
    return u.getScheme() + "://" + u.getRawAuthority() + PREFIX + suffix;
  }

  public String metadataPath() {
    return "/.well-known/oauth-authorization-server" + URI.create(issuer).getPath();
  }

  public String resourceMetadataPath() {
    return "/.well-known/oauth-protected-resource" + URI.create(resource).getPath();
  }

  public Set<String> scopes() {
    Set<String> r = new TreeSet<>();
    clients.forEach(c -> r.addAll(c.scopes));
    return Set.copyOf(r);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public boolean isDevelopmentLoopback() {
    return developmentLoopback;
  }

  public void setDevelopmentLoopback(boolean v) {
    developmentLoopback = v;
  }

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String v) {
    issuer = v;
  }

  public String getResource() {
    return resource;
  }

  public void setResource(String v) {
    resource = v;
  }

  public String getBrand() {
    return brand;
  }

  public void setBrand(String v) {
    brand = v;
  }

  public String getSigningJwk() {
    return signingJwk;
  }

  public void setSigningJwk(String v) {
    signingJwk = v;
  }

  public Path getStorageDirectory() {
    return storageDirectory;
  }

  public void setStorageDirectory(Path v) {
    storageDirectory = v;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public void setStorageKey(String v) {
    storageKey = v;
  }

  public Duration getAccessTokenTtl() {
    return accessTokenTtl;
  }

  public void setAccessTokenTtl(Duration v) {
    accessTokenTtl = v;
  }

  public Duration getRefreshTokenTtl() {
    return refreshTokenTtl;
  }

  public void setRefreshTokenTtl(Duration v) {
    refreshTokenTtl = v;
  }

  public Duration getAuthorizationTtl() {
    return authorizationTtl;
  }

  public void setAuthorizationTtl(Duration v) {
    authorizationTtl = v;
  }

  public Duration getBrowserSessionTtl() {
    return browserSessionTtl;
  }

  public void setBrowserSessionTtl(Duration v) {
    browserSessionTtl = v;
  }

  public List<Client> getClients() {
    return clients;
  }

  public void setClients(List<Client> v) {
    clients = v;
  }

  public static class Client {
    private String id;
    private String name;
    private Set<String> redirectUris = new LinkedHashSet<>(), scopes = new LinkedHashSet<>();

    public String getId() {
      return id;
    }

    public void setId(String v) {
      id = v;
    }

    public String getName() {
      return name;
    }

    public void setName(String v) {
      name = v;
    }

    public Set<String> getRedirectUris() {
      return redirectUris;
    }

    public void setRedirectUris(Set<String> v) {
      redirectUris = v;
    }

    public Set<String> getScopes() {
      return scopes;
    }

    public void setScopes(Set<String> v) {
      scopes = v;
    }
  }
}
