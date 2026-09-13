package com.cogistra.mcpbridge.api;

import java.io.IOException;
import java.io.InputStream;

public record ArtifactContent(FileArtifact artifact, InputStream stream) implements AutoCloseable {
  @Override
  public void close() throws IOException {
    stream.close();
  }
}
