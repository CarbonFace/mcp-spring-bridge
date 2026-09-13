package com.cogistra.mcpbridge.binding;

import com.cogistra.mcpbridge.api.BridgeIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;

/** Optional semantic mapping for a specific endpoint; protocol glue stays in the bridge. */
public interface BridgeContractAdapter {
  boolean supports(Method method);

  /**
   * Optional closed input schema for an explicit semantic contract, independent of the HTTP DTO.
   */
  default JsonNode inputSchema(Method method) {
    return null;
  }

  default JsonNode mapInput(Method method, JsonNode input, BridgeIdentity identity) {
    return input;
  }

  default Object mapOutput(Method method, Object output, BridgeIdentity identity) {
    return output;
  }
}
