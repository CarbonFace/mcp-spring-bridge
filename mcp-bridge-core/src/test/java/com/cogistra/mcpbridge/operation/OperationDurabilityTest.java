package com.cogistra.mcpbridge.operation;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.api.BridgeException;
import com.cogistra.mcpbridge.api.BridgeIdentity;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperationDurabilityTest {
  @TempDir Path directory;
  private final BridgeIdentity employee = identity("issuer", "employee", "desktop");

  private static BridgeIdentity identity(String issuer, String subject, String client) {
    return new BridgeIdentity(issuer, subject, client, Set.of("write"), Set.of("USER"));
  }

  private LocalFileOperationStore open() {
    return new LocalFileOperationStore(directory, 50, 1024 * 1024);
  }

  private OperationCoordinator coordinator(OperationStore store) {
    return new OperationCoordinator(
        store, Clock.systemUTC(), Duration.ofMinutes(15), 16_384, 16_384);
  }

  private OperationView execute(
      OperationCoordinator coordinator, OperationView operation, OperationCallback callback) {
    return coordinator.execute(
        employee, operation.operationId(), operation.revision(), operation.payloadHash(), callback);
  }

  @Test
  void responseLossAndRestartDoNotRepeatPersistedBusinessEffectOrLeakAcrossClients() {
    AtomicInteger effects = new AtomicInteger();
    OperationView prepared;
    try (var store = open()) {
      var engine = coordinator(store);
      prepared = engine.prepare(employee, "sample.change", Map.of("b", 2, "a", 1), "request-1");
      var same = engine.prepare(employee, "sample.change", Map.of("a", 1, "b", 2), "request-1");
      assertThat(same.operationId()).isEqualTo(prepared.operationId());
      assertThat(
              execute(
                      engine,
                      prepared,
                      snapshot -> {
                        assertThat(snapshot.arguments().get("a").asInt()).isEqualTo(1);
                        return Map.of("receipt", effects.incrementAndGet());
                      })
                  .state())
          .isEqualTo(OperationState.SUCCEEDED);
    }
    try (var store = open()) {
      var engine = coordinator(store);
      var replay = execute(engine, prepared, ignored -> effects.incrementAndGet());
      assertThat(replay.state()).isEqualTo(OperationState.SUCCEEDED);
      assertThat(replay.result().get("receipt").asInt()).isEqualTo(1);
      assertThat(effects).hasValue(1);
      for (BridgeIdentity stranger :
          List.of(
              identity("issuer", "employee", "cli"),
              identity("issuer", "other", "desktop"),
              identity("other", "employee", "desktop"))) {
        assertThatThrownBy(() -> engine.status(stranger, replay.operationId()))
            .isInstanceOf(BridgeException.class)
            .extracting(e -> ((BridgeException) e).code())
            .isEqualTo("OPERATION_NOT_FOUND");
      }
    }
  }

  @Test
  void restartPreservesExactDecimalArgumentsAndCannotTurnAChangedRequestIntoTheOriginal() {
    BigDecimal amount = new BigDecimal("12345678901234567890.12345678901234567890");
    OperationView prepared;
    try (var store = open()) {
      prepared =
          coordinator(store)
              .prepare(employee, "sample.decimal", Map.of("amount", amount), "request-decimal");
    }
    try (var store = open()) {
      var engine = coordinator(store);
      var reloaded = engine.status(employee, prepared.operationId());
      assertThat(reloaded.preview().get("amount").decimalValue()).isEqualByComparingTo(amount);
      assertThat(
              engine
                  .prepare(employee, "sample.decimal", Map.of("amount", amount), "request-decimal")
                  .payloadHash())
          .isEqualTo(prepared.payloadHash());
      assertThat(
              execute(
                      engine,
                      prepared,
                      snapshot -> {
                        assertThat(snapshot.arguments().get("amount").decimalValue())
                            .isEqualByComparingTo(amount);
                        return Map.of("amount", amount);
                      })
                  .state())
          .isEqualTo(OperationState.SUCCEEDED);
    }
  }

  @Test
  void changedContentInvalidatesOldConfirmationAndCancelledOrExpiredWorkNeverRuns() {
    try (var store = open()) {
      var engine = coordinator(store);
      var first = engine.prepare(employee, "sample.change", Map.of("value", "old"), "request-1");
      assertThatThrownBy(
              () -> engine.prepare(employee, "sample.change", Map.of("value", "new"), "request-1"))
          .isInstanceOf(BridgeException.class)
          .extracting(e -> ((BridgeException) e).code())
          .isEqualTo("REQUEST_CONFLICT");
      var revised =
          engine.revise(employee, first.operationId(), first.revision(), Map.of("value", "new"));
      AtomicInteger effects = new AtomicInteger();
      assertThatThrownBy(() -> execute(engine, first, ignored -> effects.incrementAndGet()))
          .isInstanceOf(BridgeException.class)
          .extracting(e -> ((BridgeException) e).code())
          .isEqualTo("CONFIRMATION_CHANGED");
      assertThat(revised.preview().get("value").asText()).isEqualTo("new");
      assertThat(revised.revision()).isEqualTo(first.revision() + 1);
      engine.cancel(employee, revised.operationId(), revised.revision());
      assertThat(execute(engine, revised, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(OperationState.CANCELLED);
      var prepared =
          engine.prepare(employee, "sample.change", Map.of("value", "another"), "request-2");
      var future =
          new OperationCoordinator(
              store,
              Clock.offset(Clock.systemUTC(), Duration.ofHours(1)),
              Duration.ofMinutes(15),
              16_384,
              16_384);
      assertThat(execute(future, prepared, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(OperationState.EXPIRED);
      assertThat(effects).hasValue(0);
    }
  }

  @Test
  void uncertainCommitIsNeverRetriedAndOnlyHostEvidenceResolvesIt() {
    AtomicInteger effects = new AtomicInteger();
    try (var store = open()) {
      var engine = coordinator(store);
      var prepared = engine.prepare(employee, "sample.change", Map.of("value", 1), "request-1");
      var unknown =
          execute(
              engine,
              prepared,
              ignored -> {
                effects.incrementAndGet();
                throw new IllegalStateException("sensitive host message");
              });
      assertThat(unknown.state()).isEqualTo(OperationState.UNKNOWN);
      assertThat(unknown.errorCode()).isEqualTo("EXECUTION_OUTCOME_UNKNOWN");
      assertThat(execute(engine, prepared, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(OperationState.UNKNOWN);
      assertThat(
              engine
                  .recover(
                      employee, prepared.operationId(), ignored -> RecoveryDecision.unresolved())
                  .state())
          .isEqualTo(OperationState.UNKNOWN);
      var recovered =
          engine.recover(
              employee,
              prepared.operationId(),
              ignored -> RecoveryDecision.committed(Map.of("receipt", "host-1")));
      assertThat(recovered.state()).isEqualTo(OperationState.SUCCEEDED);
      assertThat(recovered.result().get("receipt").asText()).isEqualTo("host-1");
      assertThat(effects).hasValue(1);
      assertThatThrownBy(
              () ->
                  engine.revise(
                      employee, prepared.operationId(), prepared.revision(), Map.of("value", 2)))
          .isInstanceOf(BridgeException.class);
    }
  }

  @Test
  void interruptedExecutingRecordRecoversAsUnknownAndAnotherLiveStoreIsRejected() {
    OperationView prepared;
    try (var store = open()) {
      var engine = coordinator(store);
      prepared = engine.prepare(employee, "sample.change", Map.of("value", 1), "request-1");
      assertThatThrownBy(this::open).isInstanceOf(BridgeException.class);
      store.locked(
          session -> {
            var current = session.find(prepared.operationId());
            session.put(
                current.transition(
                    OperationState.EXECUTING, null, null, System.currentTimeMillis()));
            return null;
          });
    }
    try (var restarted = open()) {
      var engine = coordinator(restarted);
      assertThat(engine.status(employee, prepared.operationId()).state())
          .isEqualTo(OperationState.UNKNOWN);
      assertThat(
              execute(
                      engine,
                      prepared,
                      ignored -> {
                        throw new AssertionError("Interrupted operation must not rerun");
                      })
                  .state())
          .isEqualTo(OperationState.UNKNOWN);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void receiptWriteFailureAfterCallbackIsUnknownAndDoesNotRetrySideEffects(
      boolean failUnknownReceipt) {
    OperationView prepared;
    AtomicInteger effects = new AtomicInteger();
    try (var durable = open()) {
      OperationStore failingReceipt =
          new OperationStore() {
            public <T> T locked(Function<Session, T> action) {
              return durable.locked(
                  real ->
                      action.apply(
                          new Session() {
                            public OperationRecord find(String id) {
                              return real.find(id);
                            }

                            public OperationRecord findRequest(
                                OperationOwner owner, String requestId) {
                              return real.findRequest(owner, requestId);
                            }

                            public List<OperationRecord> records() {
                              return real.records();
                            }

                            public void put(OperationRecord record) {
                              if (record.state() == OperationState.SUCCEEDED
                                  || failUnknownReceipt && record.state() == OperationState.UNKNOWN)
                                throw new BridgeException("DISK_FULL", "Injected receipt failure");
                              real.put(record);
                            }
                          }));
            }
          };
      var engine = coordinator(failingReceipt);
      prepared = engine.prepare(employee, "sample.change", Map.of("value", 1), "request-1");
      assertThat(execute(engine, prepared, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(OperationState.UNKNOWN);
      assertThat(engine.status(employee, prepared.operationId()).state())
          .isEqualTo(failUnknownReceipt ? OperationState.EXECUTING : OperationState.UNKNOWN);
      assertThat(execute(engine, prepared, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(failUnknownReceipt ? OperationState.EXECUTING : OperationState.UNKNOWN);
      assertThat(effects).hasValue(1);
    }
    try (var restarted = open()) {
      var observer = coordinator(restarted);
      assertThat(observer.status(employee, prepared.operationId()).state())
          .isEqualTo(OperationState.UNKNOWN);
      assertThat(execute(observer, prepared, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(OperationState.UNKNOWN);
      assertThat(effects).hasValue(1);
    }
  }

  @Test
  void sharedCoordinatorsDoNotMisclassifyLiveWorkOrLoseItsReceipt() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
    OperationView prepared;
    AtomicInteger effects = new AtomicInteger();
    try (var store = open()) {
      var engine = coordinator(store);
      var observer = coordinator(store);
      prepared = engine.prepare(employee, "sample.change", Map.of("value", 1), "request-1");
      Future<OperationView> first =
          executor.submit(
              () ->
                  execute(
                      engine,
                      prepared,
                      ignored -> {
                        effects.incrementAndGet();
                        entered.countDown();
                        if (!finish.await(5, TimeUnit.SECONDS))
                          throw new IllegalStateException("Timed out");
                        return "done";
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(observer.status(employee, prepared.operationId()).state())
          .isEqualTo(OperationState.EXECUTING);
      assertThat(execute(observer, prepared, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(OperationState.EXECUTING);
      assertThatThrownBy(
              () -> observer.cancel(employee, prepared.operationId(), prepared.revision()))
          .isInstanceOf(BridgeException.class);
      finish.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo(OperationState.SUCCEEDED);
      assertThat(observer.status(employee, prepared.operationId()).result().asText())
          .isEqualTo("done");
      assertThat(execute(observer, prepared, ignored -> effects.incrementAndGet()).state())
          .isEqualTo(OperationState.SUCCEEDED);
      assertThat(effects).hasValue(1);
    } finally {
      finish.countDown();
      executor.shutdownNow();
    }
    try (var restarted = open()) {
      var observer = coordinator(restarted);
      assertThat(observer.status(employee, prepared.operationId()).state())
          .isEqualTo(OperationState.SUCCEEDED);
      assertThat(
              execute(observer, prepared, ignored -> effects.incrementAndGet()).result().asText())
          .isEqualTo("done");
      assertThat(effects).hasValue(1);
    }
  }

  @Test
  void provenRejectionAndStorageBoundsFailBeforeUnconfirmedExecution() {
    try (var store = new LocalFileOperationStore(directory, 1, 4096)) {
      var engine = coordinator(store);
      var prepared = engine.prepare(employee, "sample.change", Map.of("value", 1), "request-1");
      var rejected =
          execute(
              engine,
              prepared,
              ignored -> {
                throw new OperationRejectedException("DOMAIN_LOCKED", "Locked");
              });
      assertThat(rejected.state()).isEqualTo(OperationState.REJECTED);
      assertThat(rejected.errorCode()).isEqualTo("DOMAIN_LOCKED");
      assertThatThrownBy(
              () -> engine.prepare(employee, "sample.change", Map.of("value", 2), "request-2"))
          .isInstanceOf(BridgeException.class)
          .extracting(e -> ((BridgeException) e).code())
          .isEqualTo("STORAGE_FULL");
    }
  }
}
