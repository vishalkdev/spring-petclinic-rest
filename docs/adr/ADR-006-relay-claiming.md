# ADR-006 · Claiming events with leases

Status: proposed

**Context.** If two app instances run, an event should be sent by only one of them. It must work on H2, HSQLDB, MySQL and Postgres, without holding a transaction during the HTTP call.

**Decision.** Claim with `UPDATE ... SET locked_until = now + 60s, locked_by = me WHERE id = ? AND status = 'PENDING' AND (locked_until IS NULL OR locked_until < now)`. One updated row means we own it. The outcome update checks `locked_by = me`.

**Alternatives.**
- `SELECT ... FOR UPDATE SKIP LOCKED`: good on Postgres/MySQL, but uneven support on H2/HSQLDB.
- ShedLock: only one instance works; adds a dependency.

**Consequences.** A duplicate send happens only if an instance dies between sending and recording the result.
