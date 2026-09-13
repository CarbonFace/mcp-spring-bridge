package com.cogistra.mcpbridge.sample;

import com.cogistra.mcpbridge.annotation.Effect;
import com.cogistra.mcpbridge.annotation.McpPolicy;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.springaicommunity.mcp.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

@Component
public class SampleNativeCapabilities {
  private final SampleRecordService records;

  public SampleNativeCapabilities(SampleRecordService records) {
    this.records = records;
  }

  @McpTool(
      name = "sample_native_record",
      description = "Read one owned record through a native Spring AI MCP declaration",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              openWorldHint = false))
  @McpPolicy(effect = Effect.READ, scopes = "sample:read")
  @PreAuthorize("hasAuthority('sample:read')")
  public SampleRecord get(
      @McpArg(name = "id", description = "Owned record identifier", required = true) String id) {
    return records.get(id);
  }

  @McpTool(
      name = "sample_native_patch",
      description =
          "Prepare the same sparse edit through native MCP annotations; requires current version")
  @McpPolicy(effect = Effect.WRITE, scopes = "sample:write")
  @PreAuthorize("hasAuthority('sample:write')")
  public SampleRecord patch(
      @McpArg(name = "id", required = true) String id,
      @McpArg(name = "patch", required = true) SamplePatch patch) {
    return records.patch(id, patch);
  }

  @McpResource(
      name = "sample_guide",
      uri = "sample://guide",
      description = "Protected synthetic example guide",
      mimeType = "text/plain")
  @McpPolicy(effect = Effect.READ, scopes = "sample:read")
  @PreAuthorize("hasAuthority('sample:read')")
  public McpSchema.ReadResourceResult guide() {
    return new McpSchema.ReadResourceResult(
        List.of(
            new McpSchema.TextResourceContents(
                "sample://guide",
                "text/plain",
                "Records belong to one account. Read the latest version before editing. A missing field is preserved; note:null clears a note.")));
  }

  @McpPrompt(
      name = "sample_review",
      description = "Protected prompt for reviewing owned synthetic records")
  @McpPolicy(effect = Effect.READ, scopes = "sample:read")
  @PreAuthorize("hasAuthority('sample:read')")
  public McpSchema.GetPromptResult review() {
    return new McpSchema.GetPromptResult(
        "Review your sample records",
        List.of(
            new McpSchema.PromptMessage(
                McpSchema.Role.USER,
                new McpSchema.TextContent(
                    "List my sample records and describe the current titles. Ask me before preparing or executing any edits."))));
  }
}
