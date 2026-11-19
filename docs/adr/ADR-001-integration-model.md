# ADR-001 · Outbox with push relay

Status: proposed

**Context.** The DB and the external system cannot share a transaction. Save-then-send loses events on a crash; send-then-save can announce a change that rolls back.

**Decision.** Write each change to an `outbox_event` table in the same transaction. A scheduled relay POSTs pending events to a configured webhook.

**Alternatives.**
- Call the external system in the request: fails or slows requests when it is down.
- Send after commit from memory (`@TransactionalEventListener` + `@Async`): lost on crash.
- Kafka/RabbitMQ: still needs an outbox, and reviewers would need Docker.
- Debezium CDC: catches all writes, but needs Kafka Connect.
- Pull feed (`GET /changes?after=`): robust, but the consumer must build a poller. Could be added on the same table later.

**Consequences.** Changes arrive within about a second, not instantly. One extra insert per changed record. Only writes through `ClinicService` are captured. The transport sits behind an interface, so Kafka can be added later.
