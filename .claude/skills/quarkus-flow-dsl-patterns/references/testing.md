# Testing workflows without flakes

Drive the engine directly (publish CloudEvents, no HTTP) for workflow behaviour, and keep one
end-to-end REST test for the journey. The engine is asynchronous, so every assertion is an *eventually*
assertion.

## A publisher helper that respects arming

The single biggest source of flakes is publishing before the engine has armed the `listen`. Encode the
wait in the helper so no test has to remember:

```java
/** Submit only once the instance is parked on a listen. */
public void answerWhenArmed(String instanceId, String answer) {
    awaitWaiting(instanceId);
    submit(instanceId, answer);
}

public void awaitWaiting(String instanceId) {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
        var s = store.instance(instanceId).map(i -> i.status()).orElse(null);
        if (s == WAITING || s == COMPLETED || s == FAULTED || s == CANCELLED) return;
        Thread.sleep(25);
    }
}

/** Wait until at least `attempt` attempts have been graded, or the gate cleared. */
public void awaitGraded(String instanceId, int attempt) { /* poll the read-model */ }
```

Then a multi-attempt loop is safe:

```java
for (int i = 1; i <= max; i++) {
    player.answerWhenArmed(instance.id(), "wrong " + i);
    player.awaitGraded(instance.id(), i);
}
```

## Assert on the trail, not only the current state

A fast path can move past the state you wanted to observe before your poll sees it. With
`ALWAYS_FAIL`-style modes the instance can enter a room and be thrown out again in milliseconds.
Record an ordered trail of states and assert on **where it has been**:

```java
await().atMost(ofSeconds(10)).until(() -> store.trail(id).stream()
        .anyMatch(v -> v.room() == Room.TRAP_CORRIDOR));
```

## Make outcomes deterministic

Give any randomness a forced mode (`RANDOM` / `ALWAYS_SUCCEED` / `ALWAYS_FAIL`) and a package-private
test hook, so a test can choose the path instead of retrying until lucky. Shorten timeouts under the
test profile (`%test.app.timeout=PT2S`) so timeout tests take seconds.

## Clean up instances between tests

An instance parked on a `listen`, or one that loops on a timeout, survives the test that created it and
starves the shared engine for later tests. Cancel and forget everything in `@AfterEach`:

```java
store.all().forEach((id, instance) -> { try { instance.cancel(); } catch (RuntimeException ignored) {} });
store.all().keySet().forEach(store::remove);
```

## Passes in isolation, fails in the suite

That signature means contention, not a regression. Confirm it:

```bash
mvn test -Dtest='MyWorkflowTest#the_failing_test'
```

If it passes alone, the shared budget is too tight under load — usually because a feature added a
round-trip per step. Widen the budget **for that test only**, via an overload, and say why:

```java
private void awaitStatus(WorkflowInstance i, WorkflowStatus s) { awaitStatus(i, s, 10); }
private void awaitStatus(WorkflowInstance i, WorkflowStatus s, int seconds) { ... }
```

Do not widen the shared default — that hides real regressions everywhere else. And if the test asserts
*isolation* rather than *speed*, a longer budget does not weaken it.

## REST-assured under `@QuarkusTest`

`RestAssured.basePath` is **not** usable to add an API prefix: the Quarkus test extension resets it per
test from `quarkus.http.root-path`, so setting it in `@BeforeAll` silently has no effect and the fix
looks right while failing identically. Put the prefix at each call site.

## Trace the engine when a test confuses you

```properties
%test.quarkus.flow.tracing.enabled=true
```

That logs every task start/completion **with its output**, which is how you discover that a `listen`
handed the next task a list rather than the bare object.
