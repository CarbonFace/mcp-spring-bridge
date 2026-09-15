package com.cogistra.mcpbridge.guidance;

/** A host's immutable published version; the digest identifies the complete deterministic ZIP. */
public record BridgeGuidanceVersion(String version, String bundleSha256) {
  public BridgeGuidanceVersion {
    if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
      throw new IllegalArgumentException("Invalid guidance version");
    if (bundleSha256 == null || !bundleSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Guidance bundle digest must be lowercase SHA-256");
  }
}
