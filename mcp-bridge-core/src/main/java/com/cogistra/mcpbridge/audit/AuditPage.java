package com.cogistra.mcpbridge.audit;

import java.util.List;

public record AuditPage(List<AuditEvent> events, String nextCursor) {
  public AuditPage {
    events = List.copyOf(events);
  }
}
