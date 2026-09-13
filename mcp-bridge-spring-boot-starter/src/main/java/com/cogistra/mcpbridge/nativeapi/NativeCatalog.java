package com.cogistra.mcpbridge.nativeapi;

import java.util.List;

public record NativeCatalog(
    List<NativeTool> tools,
    List<NativeResource> resources,
    List<NativeResourceTemplate> resourceTemplates,
    List<NativePrompt> prompts,
    List<NativeCompletion> completions) {
  public NativeCatalog {
    tools = List.copyOf(tools);
    resources = List.copyOf(resources);
    resourceTemplates = List.copyOf(resourceTemplates);
    prompts = List.copyOf(prompts);
    completions = List.copyOf(completions);
  }
}
