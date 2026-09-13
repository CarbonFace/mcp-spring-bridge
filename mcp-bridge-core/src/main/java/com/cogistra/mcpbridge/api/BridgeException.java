package com.cogistra.mcpbridge.api;

/** Only deliberately safe messages are returned to MCP clients. */
public class BridgeException extends RuntimeException {
  private final String code;

  public BridgeException(String code, String safeMessage) {
    super(safeMessage);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
