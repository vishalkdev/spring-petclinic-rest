# ADR-003 · At-least-once, ordered per record

Status: proposed

**Context.** Over HTTP we cannot tell "processed but response lost" from "never received", so exactly-once is not possible.

**Decision.** Deliver at least once. The event id never changes and is also sent as `Idempotency-Key`. An event is sent only when no older `PENDING` event exists for the same record. A `FAILED` event does not block newer ones. Consumers ignore events whose `seq` is not higher than the last one applied.

**Alternatives.**
- No ordering: consumer briefly applies older state.
- Single global queue: one bad event stops everything.

**Consequences.** Duplicates are possible after a crash mid-send or a manual re-queue. `seq` shows an event is stale; it does not show a gap.
