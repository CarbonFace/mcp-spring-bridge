package com.cogistra.mcpbridge.nativeapi;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;

/** Declaration only; the central registry must wrap this callback before publishing it. */
public record NativeTool(NativeSource source, SyncToolSpecification specification) {}
