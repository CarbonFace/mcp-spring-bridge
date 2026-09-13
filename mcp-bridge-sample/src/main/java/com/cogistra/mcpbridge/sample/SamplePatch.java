package com.cogistra.mcpbridge.sample;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Missing keys preserve saved values; explicit note:null clears a note; title cannot be cleared.
 */
public record SamplePatch(@Min(1) long version, @NotNull Map<String, JsonNode> changes) {}
