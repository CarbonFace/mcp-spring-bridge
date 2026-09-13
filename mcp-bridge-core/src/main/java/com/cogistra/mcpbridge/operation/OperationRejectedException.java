package com.cogistra.mcpbridge.operation;

import com.cogistra.mcpbridge.api.BridgeException;

/**
 * Only throw when the host can prove no business side effect occurred (including committed external
 * calls).
 */
public final class OperationRejectedException extends BridgeException {
  public OperationRejectedException(String code, String safeMessage) {
    super(code, safeMessage);
  }
}
