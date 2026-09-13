package com.cogistra.mcpbridge.nativeapi;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncCompletionSpecification;

/** Declaration only; the central registry must wrap this callback before publishing it. */
public record NativeCompletion(NativeSource source, SyncCompletionSpecification specification) {}
