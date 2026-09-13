package com.cogistra.mcpbridge.audit;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.api.BridgeException;
import com.cogistra.mcpbridge.api.BridgeIdentity;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditPersistenceTest {
  @TempDir Path directory;

  private BridgeIdentity identity(String subject, String client) {
    return new BridgeIdentity("issuer", subject, client, Set.of("audit:read"), Set.of());
  }

  private AuditEvent event(BridgeIdentity identity) {
    return AuditEvent.create(
        identity,
        "sample.change",
        null,
        AuditEvent.Phase.EXECUTE,
        AuditEvent.Outcome.SUCCEEDED,
        null);
  }

  @Test
  void restartedAuditPagesAreStrictlyEmployeeAndClientScoped() {
    var employee = identity("employee", "desktop");
    var otherClient = identity("employee", "cli");
    var otherEmployee = identity("other", "desktop");
    String cursor;
    try (var audit = new LocalFileAuditRepository(directory, 10, 16384)) {
      audit.append(event(employee));
      audit.append(event(otherClient));
      audit.append(event(otherEmployee));
      audit.append(event(employee));
      audit.append(event(employee));
      var first = audit.query(employee, null, 2);
      assertThat(first.events())
          .hasSize(2)
          .allSatisfy(e -> assertThat(e.owner().subject()).isEqualTo("employee"));
      cursor = first.nextCursor();
      assertThat(cursor).isNotBlank();
      assertThat(audit.query(otherClient, null, 10).events()).hasSize(1);
      assertThatThrownBy(() -> audit.query(otherClient, cursor, 2))
          .isInstanceOf(BridgeException.class);
      assertThatThrownBy(() -> new LocalFileAuditRepository(directory, 10, 16384))
          .isInstanceOf(BridgeException.class);
    }
    try (var audit = new LocalFileAuditRepository(directory, 10, 16384)) {
      var next = audit.query(employee, cursor, 2);
      assertThat(next.events()).hasSize(1);
      assertThat(next.nextCursor()).isNull();
      assertThat(audit.query(identity("nobody", "desktop"), null, 10).events()).isEmpty();
    }
  }

  @Test
  void fullAuditDoesNotSilentlyDropEventsAndRetryOfSameEventIsIdempotent() {
    var employee = identity("employee", "desktop");
    try (var audit = new LocalFileAuditRepository(directory, 1, 4096)) {
      var first = event(employee);
      audit.append(first);
      audit.append(first);
      assertThat(audit.query(employee, null, 1).events()).containsExactly(first);
      assertThatThrownBy(() -> audit.append(event(employee)))
          .isInstanceOf(BridgeException.class)
          .extracting(e -> ((BridgeException) e).code())
          .isEqualTo("AUDIT_FULL");
      assertThatThrownBy(() -> audit.query(employee, null, 201))
          .isInstanceOf(BridgeException.class);
      assertThatThrownBy(
              () ->
                  AuditEvent.create(
                      employee,
                      "sample.change",
                      null,
                      AuditEvent.Phase.EXECUTE,
                      AuditEvent.Outcome.FAILED,
                      "password: sensitive argument"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
