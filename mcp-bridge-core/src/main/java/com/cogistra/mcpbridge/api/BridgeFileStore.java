package com.cogistra.mcpbridge.api;

import java.io.InputStream;

/** Implementations enforce ownership and bounded storage. Paths never come from the caller. */
public interface BridgeFileStore {
  FileArtifact store(BridgeIdentity owner, String filename, String mediaType, InputStream contents);

  ArtifactContent open(BridgeIdentity caller, String fileId);

  void delete(BridgeIdentity caller, String fileId);
}
