package com.cogistra.mcpbridge.api;

import java.time.Instant;

public record FileArtifact(
    String fileId, String filename, String mediaType, long size, String url, Instant expiresAt) {}
