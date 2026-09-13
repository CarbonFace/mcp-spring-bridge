package com.cogistra.mcpbridge.boot;

import com.cogistra.mcpbridge.annotation.Effect;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.bridge")
public class BridgeProperties {
  private boolean enabled = true;
  private boolean publishResourceMetadata = true;
  private String path = "/mcp";
  private String name = "spring-mcp-bridge";
  private Path storage = Path.of(".local/mcp-bridge");
  private Duration confirmationTtl = Duration.ofMinutes(20);
  private int maxArgumentBytes = 1024 * 1024;
  private int maxResultBytes = 2 * 1024 * 1024;
  private int maxRecords = 10000;
  private long maxStorageBytes = 256L * 1024 * 1024;
  private long maxExportBytes = 16L * 1024 * 1024;
  private Set<String> allowedOrigins = new HashSet<>();
  private final Map<String, ToolPolicy> tools = new LinkedHashMap<>();
  private String resource;
  private List<String> authorizationServers = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public boolean isPublishResourceMetadata() {
    return publishResourceMetadata;
  }

  public void setPublishResourceMetadata(boolean v) {
    publishResourceMetadata = v;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String v) {
    path = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public Path getStorage() {
    return storage;
  }

  public void setStorage(Path v) {
    storage = v;
  }

  public Duration getConfirmationTtl() {
    return confirmationTtl;
  }

  public void setConfirmationTtl(Duration v) {
    confirmationTtl = v;
  }

  public int getMaxArgumentBytes() {
    return maxArgumentBytes;
  }

  public void setMaxArgumentBytes(int v) {
    maxArgumentBytes = v;
  }

  public int getMaxResultBytes() {
    return maxResultBytes;
  }

  public void setMaxResultBytes(int v) {
    maxResultBytes = v;
  }

  public int getMaxRecords() {
    return maxRecords;
  }

  public void setMaxRecords(int v) {
    maxRecords = v;
  }

  public long getMaxStorageBytes() {
    return maxStorageBytes;
  }

  public void setMaxStorageBytes(long v) {
    maxStorageBytes = v;
  }

  public long getMaxExportBytes() {
    return maxExportBytes;
  }

  public void setMaxExportBytes(long v) {
    maxExportBytes = v;
  }

  public Set<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  public void setAllowedOrigins(Set<String> v) {
    allowedOrigins = v;
  }

  public Map<String, ToolPolicy> getTools() {
    return tools;
  }

  public String getResource() {
    return resource;
  }

  public void setResource(String v) {
    resource = v;
  }

  public List<String> getAuthorizationServers() {
    return authorizationServers;
  }

  public void setAuthorizationServers(List<String> v) {
    authorizationServers = v;
  }

  public static class ToolPolicy {
    private boolean enabled = true;
    private Effect effect;
    private Set<String> scopes = new HashSet<>();
    private Set<String> authorities = new HashSet<>();
    private boolean allowAuthenticated;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }

    public Effect getEffect() {
      return effect;
    }

    public void setEffect(Effect v) {
      effect = v;
    }

    public Set<String> getScopes() {
      return scopes;
    }

    public void setScopes(Set<String> v) {
      scopes = v;
    }

    public Set<String> getAuthorities() {
      return authorities;
    }

    public void setAuthorities(Set<String> v) {
      authorities = v;
    }

    public boolean isAllowAuthenticated() {
      return allowAuthenticated;
    }

    public void setAllowAuthenticated(boolean v) {
      allowAuthenticated = v;
    }
  }
}
