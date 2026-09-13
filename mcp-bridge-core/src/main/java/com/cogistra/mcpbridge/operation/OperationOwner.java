package com.cogistra.mcpbridge.operation;

import com.cogistra.mcpbridge.api.BridgeIdentity;
import java.util.Objects;

/** A client grant's employee identity, deliberately independent of token lifetime. */
public record OperationOwner(String issuer, String subject, String clientId) {
  public OperationOwner {
    Objects.requireNonNull(issuer);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(clientId);
  }

  public static OperationOwner from(BridgeIdentity identity) {
    Objects.requireNonNull(identity, "Verified identity required");
    return new OperationOwner(identity.issuer(), identity.subject(), identity.clientId());
  }
}
