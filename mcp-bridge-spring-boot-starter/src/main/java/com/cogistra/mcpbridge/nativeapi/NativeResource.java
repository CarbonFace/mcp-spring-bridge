package com.cogistra.mcpbridge.nativeapi;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;

/** Declaration only; the central registry must wrap this callback before publishing it. */
public record NativeResource(NativeSource source, SyncResourceSpecification specification) {}
