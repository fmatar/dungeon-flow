---
name: Quarkus Flow DSL patterns
description: Patterns for modelling behaviour in the Quarkus Flow / CNCF Serverless Workflow DSL — event waits, data switches, multi-event joins, bounded retry with compensation — and the event-timing races that make a correct workflow appear broken. Use when writing or debugging a Quarkus Flow workflow, a serverless-workflow definition, when a workflow instance hangs in WAITING, when a published CloudEvent seems to be ignored, or when deciding what belongs in the workflow versus in Java.
version: 1.0.0
---

# Quarkus Flow DSL patterns

Two things matter more than anything else in this DSL: **what you put in the workflow versus in Java**,
and **when an event can actually be received**. Get the first wrong and the workflow becomes
decoration. Get the second wrong and a correct workflow hangs forever, looking like a routing bug.

## The dividing line: routing belongs in the workflow

Every decision about *what happens next* belongs in the DSL. Java beans may **observe** or **compute**,
never route.

| Java may | Java may not |
|---|---|
| answer "did this succeed?" / "how close was that?" | decide which state comes next |
| project state for display (a read-model) | branch on that state |
| publish events, hold in-memory session state | own the retry counter or the exhaustion rule |

The test: if you find yourself writing an `if` in Java that selects the next step, that `if` belongs in
a `switchCase`. Otherwise the workflow diagram stops describing the system, which defeats the point of
using a workflow engine at all.

Services return **data**; the workflow routes on it:

```java
// service: computation only — it returns how it went, and decides nothing
public GateState grade(GateState state, Answer answer) {
    double proximity = proximity(answer);
    return state.graded(proximity >= THRESHOLD, proximity);
}
```

```java
switchWhenOrElse("Verdict", (GateState s) -> s.solved(), "Proceed", "RetryOrGiveUp", GateState.class),
switchWhenOrElse("RetryOrGiveUp", (GateState s) -> s.attempt() >= maxTries, "Compensate", "AwaitAnswer", GateState.class),
```

## The reusable shape: listen → grade → verdict → retry or compensate

One shape covers most human-in-the-loop gates: an event wait, a switch on the graded result, a bounded
retry, and a compensation when attempts run out.

```
Pose ──▶ AwaitAnswer (listen) ──▶ Grade ──▶ Verdict ──┬─(ok)──▶ Proceed
             ▲                                        │
             └────────── (wrong, attempts left) ──────┤
                                                      └─(exhausted)─▶ Compensate ──▶ back
```

Worth noticing: **the same shape describes completely unrelated fictions** — a lock that jams, a riddle
that gates a door, an approval step that times out. Reusing it deliberately is how a reader learns the
primitive rather than the story.

## Carrying state across a `listen`

A `listen` replaces the workflow data with the event's payload, so state posed *before* the wait is not
available *after* it. Two workable approaches:

**Preferred — keep the payload out of it.** Record the submission in a read-model bean *before*
publishing, and make the event a **pure trigger** with an empty body. The grading step reads the stored
submission and returns state the switches route on.

```java
// resource: stash first, then fire a trigger-only event
store.submitAnswer(id, body.answer());
publish(id, GameEvents.RIDDLE_ANSWER, "{}".getBytes());
```

This avoids coupling the workflow to event-payload shapes, which the engine wraps differently depending
on how a `listen` is composed — a `listen` can hand the next task a **list**, not the bare object.

**Alternative — type the task's input and output** so state flows through as data:

```java
withInstanceId("Grade", (id, in) -> grade(store, service, id), Object.class, GateState.class)
        .then("Verdict"),
```

If you do route on a listen's own output, verify the shape it actually produces (turn on
`quarkus.flow.tracing.enabled` and read the task output) rather than assuming.

## The event-timing race that wastes the most time

**A task that publishes runs BEFORE the engine arms the next `listen`.** Publish into that window and
nothing is listening: the event is dropped, the instance waits forever, and it looks exactly like a
routing bug.

This bites in three places:

1. **Right after starting an instance.** Block until the instance reports `WAITING` (or terminal)
   before returning from your start endpoint, so the first move cannot be lost:
   ```java
   while (System.nanoTime() < deadline) {
       WorkflowStatus s = instance.status();
       if (s == WAITING || s == COMPLETED || s == FAULTED || s == CANCELLED) return;
       Thread.sleep(20);
   }
   ```
2. **Inside a retry loop.** Each wrong answer returns to the same `listen`, which must be re-armed
   before the next submission. Submitting two answers in a tight loop drops the second.
3. **In tests.** Waiting for the *posed* state is not enough — the state is published before the
   listen is armed. Wait for `WAITING` too, then submit; and wait for each attempt to be graded before
   sending the next.

```java
public void answerWhenArmed(String instanceId, String answer) {
    awaitWaiting(instanceId);   // status == WAITING, i.e. parked on the listen
    riddleAnswer(instanceId, answer);
}
```

A test that fails only in the full suite and passes in isolation is usually this, or plain contention —
see `references/testing.md`.

## Timeouts and compensation

Wrap a `listen` with a task `timeout` inside a `try`, and catch the CNCF timeout error type so a
timeout routes somewhere sensible instead of faulting the instance:

```java
static final String TIMEOUT_ERROR = "https://serverlessworkflow.io/spec/1.0.0/errors/timeout";

tryCatch("ForkWait", t -> t
        .tryCatch(inner -> listen("WaitChoice", toOne(consumed(CHOICE)
                        .extensionByInstanceId(CORRELATION_ATTR)))
                .timeout(torch).accept(inner))
        .catchType(TIMEOUT_ERROR,
                withInstanceId("TimedOut", (id, in) -> store.enter(id, TIMED_OUT), Object.class)
                        .then("Start")))
```

A "compensation" here is just a state that puts the instance somewhere recoverable. It does not have to
be a framework feature to teach the concept — an explicit loop plus a respawn state is more legible in
the diagram than a hidden retry policy, which is a reason to prefer it in a demo.

## Correlation

Correlate events to instances with a CloudEvent extension attribute, **lowercase** per the CE spec:

```java
public static final String CORRELATION_ATTR = "dungeoninstance";   // must be lowercase
```

Filter on it in the `listen` (`extensionByInstanceId(...)`) and stamp it on every published event.
Correlating on the raw instance id is the simplest thing that works; moving to a domain id later is a
change in the events class and the publisher only.

## Multi-event join

```java
listen("WaitBoth", to().all(
        consumed(LEVER_A).extensionByInstanceId(CORRELATION_ATTR),
        consumed(LEVER_B).extensionByInstanceId(CORRELATION_ATTR)))
        .then("Next"),
```

Assert both orders **and** that one event alone does not release the gate — the second assertion is the
one that catches a broken join, and it is the one people leave out.

## Native builds

Register every record that crosses a boundary — workflow data types and REST response types:

```java
@RegisterForReflection(targets = { GateState.class, PlayerMove.class, View.class })
```

Missing registration is invisible to the JVM build and to the whole test suite, and 500s every response
in native only.

## References

- `references/testing.md` — driving workflows in tests without flakes
