package com.cogistra.mcpbridge.audit;

import com.cogistra.mcpbridge.api.BridgeIdentity;

/** Host identity must be resolved from trusted authentication, never tool input. */
public interface AuditRepository extends AutoCloseable {
  void append(AuditEvent event);

  /** Chronological cursor pagination, restricted to issuer + subject + clientId by default. */
  AuditPage query(BridgeIdentity identity, String cursor, int limit);

  @Override
  default void close() {}
}
