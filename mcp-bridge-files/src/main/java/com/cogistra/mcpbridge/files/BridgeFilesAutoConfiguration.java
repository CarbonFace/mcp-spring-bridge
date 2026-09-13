package com.cogistra.mcpbridge.files;

import com.cogistra.mcpbridge.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(BridgeFilesProperties.class)
@ConditionalOnProperty(
    prefix = "mcp.bridge",
    name = {"enabled", "files.enabled"},
    havingValue = "true",
    matchIfMissing = true)
public class BridgeFilesAutoConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(BridgeFileStore.class)
  public LocalBridgeFileStore localBridgeFileStore(BridgeFilesProperties p) {
    return new LocalBridgeFileStore(p);
  }

  @Bean
  @ConditionalOnBean(LocalBridgeFileStore.class)
  @ConditionalOnMissingBean
  public FileUploadService fileUploadService(LocalBridgeFileStore store) {
    return new FileUploadService(store);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  public BridgeFileController bridgeFileController(
      BridgeFileStore store,
      ObjectProvider<BridgeIdentityResolver> identities,
      ObjectProvider<com.cogistra.mcpbridge.audit.AuditRepository> audit) {
    return new BridgeFileController(store, identities, audit);
  }
}
