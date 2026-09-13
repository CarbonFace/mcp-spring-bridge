package com.cogistra.mcpbridge.files;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.bridge.files")
public class BridgeFilesProperties {
  private boolean enabled = true;
  private Path directory = Path.of(".local/mcp-bridge/files");
  private long maxFileBytes = 16 * 1024 * 1024;
  private long ownerQuotaBytes = 128 * 1024 * 1024;
  private long totalQuotaBytes = 1024L * 1024 * 1024;
  private int maxFiles = 4096;
  private int maxOwnerFiles = 128;
  private int maxChunkBytes = 256 * 1024;
  private Duration ttl = Duration.ofHours(24);
  private Duration uploadTtl = Duration.ofHours(1);
  private Duration cleanupInterval = Duration.ofMinutes(5);
  private String downloadUrlPrefix = "/mcp-bridge/files";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public Path getDirectory() {
    return directory;
  }

  public void setDirectory(Path v) {
    directory = v;
  }

  public long getMaxFileBytes() {
    return maxFileBytes;
  }

  public void setMaxFileBytes(long v) {
    maxFileBytes = v;
  }

  public long getOwnerQuotaBytes() {
    return ownerQuotaBytes;
  }

  public void setOwnerQuotaBytes(long v) {
    ownerQuotaBytes = v;
  }

  public long getTotalQuotaBytes() {
    return totalQuotaBytes;
  }

  public void setTotalQuotaBytes(long v) {
    totalQuotaBytes = v;
  }

  public int getMaxFiles() {
    return maxFiles;
  }

  public void setMaxFiles(int v) {
    maxFiles = v;
  }

  public int getMaxOwnerFiles() {
    return maxOwnerFiles;
  }

  public void setMaxOwnerFiles(int v) {
    maxOwnerFiles = v;
  }

  public int getMaxChunkBytes() {
    return maxChunkBytes;
  }

  public void setMaxChunkBytes(int v) {
    maxChunkBytes = v;
  }

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration v) {
    ttl = v;
  }

  public Duration getUploadTtl() {
    return uploadTtl;
  }

  public void setUploadTtl(Duration v) {
    uploadTtl = v;
  }

  public Duration getCleanupInterval() {
    return cleanupInterval;
  }

  public void setCleanupInterval(Duration v) {
    cleanupInterval = v;
  }

  public String getDownloadUrlPrefix() {
    return downloadUrlPrefix;
  }

  public void setDownloadUrlPrefix(String v) {
    downloadUrlPrefix = v;
  }

  public void validate() {
    if (directory == null
        || maxFileBytes < 1
        || maxFileBytes > Integer.MAX_VALUE
        || ownerQuotaBytes < maxFileBytes
        || totalQuotaBytes < ownerQuotaBytes
        || maxFiles < 1
        || maxOwnerFiles < 1
        || maxOwnerFiles > maxFiles
        || maxChunkBytes < 1
        || maxChunkBytes > maxFileBytes
        || ttl == null
        || ttl.isNegative()
        || ttl.isZero()
        || uploadTtl == null
        || uploadTtl.isNegative()
        || uploadTtl.isZero()
        || cleanupInterval == null
        || cleanupInterval.toMillis() < 1
        || downloadUrlPrefix == null
        || !(downloadUrlPrefix.startsWith("/") && !downloadUrlPrefix.startsWith("//")
            || downloadUrlPrefix.startsWith("https://"))
        || downloadUrlPrefix.matches(".*[\\r\\n?#].*"))
      throw new IllegalArgumentException("Invalid mcp.bridge.files limits");
  }
}
