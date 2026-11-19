# Approach

## Problem

PetClinic's database and the external system cannot share a transaction. "Save, then send" loses a change if the app stops after the commit; "send, then save" can announce a change that is rolled back.

## Solution

**Transactional outbox with a push relay.**

1. `ClinicServiceImpl` records an event row in `outbox_event` in the same transaction as the business change.
2. A scheduled relay picks due events, sends each one as a CloudEvent to a webhook, and records the result.
3. Failures are retried with backoff; events that cannot be delivered are parked as FAILED and can be re-queued by an operator.

The request thread never calls the external system. The only new cost on the request path is one insert per changed record.

## Guarantees

| Requirement | How |
|---|---|
| Changes become available | Event row commits with the change; relay delivers it |
| No silent loss | Retry schedule is stored in the row; FAILED events are kept and visible |
| Duplicates | At-least-once; stable event id + `Idempotency-Key`; consumer dedupes by `seq` |
| Main workflow not fragile | No external I/O in requests; health stays UP during outages |
| Observable and recoverable | Metrics, per-attempt logs, health details, admin API to re-queue |

## Main trade-offs

- **Eventual consistency.** The consumer is about one poll interval (1 s) behind.
- **At-least-once, not exactly-once.** A crash between sending and recording the result causes a resend.
- **Ordering is per record only.** Across records, order is not guaranteed.
- **Only writes through `ClinicService` are captured.** Direct SQL changes are not.
- **Personal data** (owner contact details) is sent to the external system.
- **A table to operate.** It is indexed, monitored by the oldest-pending-age metric, and old DELIVERED rows are purged.

## Alternatives considered

| Option | Why not (for this slice) |
|---|---|
| Call the external system in the request | Fails or slows requests when it is down |
| Send after commit from memory (`@TransactionalEventListener` + `@Async`) | Lost on crash |
| Kafka / RabbitMQ | Still needs an outbox; reviewers would need Docker |
| Debezium CDC | Catches all writes but needs Kafka Connect |
| Pull change feed (`GET /changes?after=`) | Robust, but the consumer must build a poller; could be added on the same table |

## Found and fixed along the way

Recording forced Hibernate to flush earlier than before, which exposed two existing bugs under the JPA profiles (00-context F7, F8):

- `Pet.type` used `cascade = ALL`, so deleting a pet or owner also tried to delete the shared pet type.
- Deleted pets and visits were re-persisted through their parent's cascading collection; under the `jpa` profile a visit delete most likely never reached the database.

## Implemented vs proposed

**Implemented:** T1 to T9 in `docs/sdd/03-tasks.md`, verified in `docs/sdd/04-verification.md`.

**Proposed, not implemented:**
- Kafka adapter behind `EventPublisher`; pull change feed over the same table
- Request signing (HMAC) or mTLS; secret from a vault
- PII review and field filtering
- Flyway/Liquibase instead of schema scripts
- `SELECT ... FOR UPDATE SKIP LOCKED` on Postgres/MySQL; relay parallelism
- Time-based retry budget and a circuit breaker
- Events for pet types, vets and specialties
- Consumer contract tests
- Alerts and an operator runbook
