package com.cogistra.mcpbridge.files;

import com.cogistra.mcpbridge.api.*;
import java.io.*;
import java.nio.file.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;

public final class FileAdapters {
  private FileAdapters() {}

  /** Every stream open rechecks ownership and expiry. Caller closes returned streams. */
  public static MultipartFile multipart(
      BridgeFileStore store, BridgeIdentity identity, String fileId) {
    final FileArtifact metadata;
    try (ArtifactContent content = store.open(identity, fileId)) {
      metadata = content.artifact();
    } catch (IOException e) {
      throw LocalBridgeFileStore.failure();
    }
    return new MultipartFile() {
      public String getName() {
        return "file";
      }

      public String getOriginalFilename() {
        return metadata.filename();
      }

      public String getContentType() {
        return metadata.mediaType();
      }

      public boolean isEmpty() {
        return metadata.size() == 0;
      }

      public long getSize() {
        return metadata.size();
      }

      public byte[] getBytes() throws IOException {
        try (InputStream in = getInputStream()) {
          return in.readAllBytes();
        }
      }

      public InputStream getInputStream() {
        return store.open(identity, fileId).stream();
      }

      public void transferTo(File destination) throws IOException {
        transferTo(destination.toPath());
      }

      public void transferTo(Path destination) throws IOException {
        try (InputStream in = getInputStream()) {
          Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    };
  }

  public static FileArtifact fromResponseEntity(
      BridgeFileStore store, BridgeIdentity identity, ResponseEntity<byte[]> response) {
    if (!response.getStatusCode().is2xxSuccessful())
      throw new BridgeException("EXPORT_FAILED", "The export endpoint rejected the request");
    return store.store(
        identity,
        filename(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION), "export"),
        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
        new ByteArrayInputStream(response.getBody() == null ? new byte[0] : response.getBody()));
  }

  static String filename(String disposition, String fallback) {
    if (disposition != null)
      try {
        String name = ContentDisposition.parse(disposition).getFilename();
        if (name != null) return name;
      } catch (IllegalArgumentException ignored) {
      }
    return fallback;
  }
}
