package com.cogistra.mcpbridge.nativeapi;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;

/** Declaration only; the central registry must wrap this callback before publishing it. */
public record NativePrompt(NativeSource source, SyncPromptSpecification specification) {}
