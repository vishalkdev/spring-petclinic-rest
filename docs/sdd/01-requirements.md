# 01 · Requirements

Status: draft for review · Step 1 of the SDD process
Inputs: assignment brief (requirements 1–5), `00-context.md` (facts F1–F6, questions Q1–Q3)
Output: testable requirements, decisions on Q1–Q3, assumptions, risks, and the questions handed to design.

Requirements here are **transport-neutral** on purpose. *Publish* means "make an event available to the external system"; how (push, pull, broker) is decided in `02-design.md` / ADR-001.

## 1. Problem statement

An external system must learn about relevant changes to PetClinic data. PetClinic's database and the external system cannot share a transaction, so a naive "save, then send" can lose a change (crash after commit) or announce one that never happened (send before a rollback). The solution must make every relevant change available eventually, tolerate the external system being down, make failures visible and recoverable, and not make PetClinic's own REST workflow depend on the external system.

## 2. Scope

**In scope**
- Create, update and delete of **Owner**, **Pet** and **Visit**.
- Pets and visits removed as a side effect of another delete (owner, pet, pet type). See D1, D2.
- Durable recording, asynchronous publication, retry, parking of failed events, operator recovery, metrics and logs.

**Out of scope (documented follow-up)**
- Events for PetType, Vet and Specialty records themselves (D2).
- Multiple subscribers, consumer-specific filtering, schema registry.
- Changes made outside `ClinicService` (direct SQL, data scripts, other applications).
- Field-level change diffs.

**Non-goals**
- Exactly-once delivery. The guarantee is at-least-once with consumer-side deduplication (CR-1..CR-3).
- Global ordering across different records.
- Changing existing REST behaviour, status codes or schemas of business tables.

## 3. Terms

| Term | Meaning |
|---|---|
| Record | One Owner, Pet or Visit row. |
| Subject | Record type and id, e.g. `owner/7`. Unit of ordering and deduplication. |
| Change event | Immutable description of one change to one subject. |
| Event id | Globally unique id of a change event; stable across every delivery attempt. |
| Sequence | Monotonically increasing number assigned when the event is recorded (D3). |
| Pending / Delivered / Failed | Event states: waiting to be published / acknowledged by the external system / parked after a permanent error or exhausted retries. |
| Operator | A person with admin rights who inspects and recovers failed events. |

## 4. Decisions on the questions from 00-context

### D1 · Cascaded deletes: one delete event per removed record (Q1)

When a delete removes dependent records, the system records a delete event for **each** removed record, in the same transaction, with a `cause` naming the subject whose deletion triggered it.

- *Why:* the external system ends up mirroring exactly the records PetClinic has, with one uniform rule ("delete by subject"), and without having to know PetClinic's cascade rules (F2, F3).
- *Rejected: one parent event listing child ids.* Puts PetClinic's internal cascade rules into the integration contract; a later cascade change would silently break consumers; gives the consumer two delete paths to keep consistent.
- *Rejected: parent event only.* Pets and visits removed by a pet-type delete would disappear with no event at all, which violates brief requirement 2.
- *Consequence:* dependants must be enumerated **before** the delete executes. Owner → pets and Pet → visits are eagerly loaded today; pet-type → pets needs a lookup.

### D2 · Reference data: only side effects on in-scope records (Q2)

PetType, Vet and Specialty changes produce no events of their own. A **PetType delete** produces delete events for every pet and visit it removes (via D1, `cause = pettype/<id>`).

- *Why:* the brief names owners, pets and visits and allows leaving out parts that "do not give useful information"; extra event types would reuse the same mechanism with no new design content.
- *Evidence that the line is safe:* deleting a Vet or Specialty touches no in-scope table. `visits` has no vet reference; only `vet_specialties` cascades (`db/h2/schema.sql`). Deleting a PetType does touch in-scope tables (F3), so its side effects are in scope.
- *Follow-up:* the same recorder can emit `pettype.*`, `vet.*`, `specialty.*` events.

### D3 · Sequence source: the event's insertion order in the event store (Q3)

Each event's sequence is the auto-increment key assigned when the event row is inserted.

- *Why:* no change to business tables or REST behaviour. Per-subject ordering holds because concurrent transactions changing the same record serialise on that record's row lock, and each transaction records its event after its business write, so the later commit receives the higher sequence.
- *Rejected: JPA `@Version` on entities.* Changes four schema files plus `data.sql`, needs manual version handling in the JDBC repositories, and turns previously-successful concurrent edits into optimistic-lock failures, i.e. new REST error behaviour. That conflicts with brief requirement 4 and with "no unrelated changes". Candidate production follow-up with its own ADR.
- *Limits (accepted):* sequences are ordered per subject but **not contiguous**, so they detect stale events but not missing ones. Across subjects, sequence order may differ from commit order; only per-subject order is promised.

## 5. Functional requirements

EARS style. "Verify" names the planned evidence; concrete tests are listed in `04-verification.md`.

| ID | Requirement | Verify |
|---|---|---|
| FR-1 | When an Owner, Pet or Visit is created, updated or deleted through `ClinicService` and the transaction commits, the system shall have recorded exactly one change event for that record in the same transaction. | Integration, per record type and operation |
| FR-2 | When a delete removes dependent Pets or Visits (owner delete, pet delete, pet-type delete), the system shall record one delete event per removed record, each with `cause` set to the deleted parent's subject, in the same transaction. | Integration, three cascade cases |
| FR-3 | If the business transaction rolls back, the system shall have recorded no change event for it. | Integration, forced failure after save |
| FR-4 | The system shall record events for these changes under every repository profile (`jdbc`, `jpa`, `spring-data-jpa`). | Integration run on ≥2 profiles |
| FR-5 | For create and update, the change event shall carry a full snapshot of the record's state after the change. For delete, it shall carry the record id and its parent reference. | Unit (mapper) + integration |
| FR-6 | Each change event shall carry: event id, event type with a version suffix (e.g. `petclinic.owner.updated.v1`), subject, sequence, time of recording, payload, and `cause` when applicable. | Unit (envelope) |
| FR-7 | A snapshot shall reference related records by id only (Pet → `ownerId`, `typeId`; Visit → `petId`) and shall not embed them. | Unit (mapper) |
| FR-8 | The system shall publish every recorded event to the external system asynchronously, at least once, unless the event is Failed. | End-to-end with a stub external system |
| FR-9 | When the external system acknowledges an event, the system shall mark it Delivered and shall not publish it again in normal operation. | End-to-end |

## 6. Reliability and operational requirements

| ID | Requirement | Brief req | Verify |
|---|---|---|---|
| NFR-1 | While the external system is unavailable, slow or returning errors, PetClinic REST write endpoints shall return the same status codes as the baseline, and request processing shall perform no I/O to the external system. | 4 | Integration with stub down / delayed |
| NFR-2 | Recording an event shall be a local write to the PetClinic database inside the business transaction. | 1, 2, 4 | Code review + FR-3 test |
| NFR-3 | If a publish attempt fails with a retryable error, the system shall retry with exponential backoff and jitter up to a configured budget, and the retry schedule shall be persisted so that it survives an application restart. | 2, 5 | Integration (fail, fail, succeed); restart test |
| NFR-4 | If a publish attempt fails with a permanent error, or the retry budget is exhausted, the system shall mark the event Failed, keep it with the last error and attempt count, and log it at ERROR level. | 2, 5 | Integration |
| NFR-5 | The system shall never delete an event that is not Delivered. | 2 | Integration (retention job) |
| NFR-6 | Events for the same subject shall be published in sequence order. While an earlier event for a subject is pending retry, later events for that subject shall not be published. | 3 | Integration (two updates, first retried) |
| NFR-7 | When an earlier event for a subject is Failed, later events for that subject shall continue to be published. *(Snapshots make the later event the newer truth; the consumer discards the older one if it is replayed — CR-3.)* | 2, 3 | Integration |
| NFR-8 | The system shall expose metrics for: number of Pending events, number of Failed events, age of the oldest Pending event, and publish attempts by outcome (success / retry / failed). | 5 | Integration (meter registry) |
| NFR-9 | The system shall log every publish attempt with event id, subject, attempt number and outcome. | 5 | Review + test log capture (optional) |
| NFR-10 | An operator shall be able to list Failed events and re-queue one or all of them without a restart. A re-queued event shall keep its event id and sequence. | 5 | Integration |
| NFR-11 | A backlog or a Failed event shall not make the application report itself unhealthy (DOWN). | 4 | Integration (health endpoint) |
| NFR-12 | At most one relay worker shall be publishing a given event at a time, including when several application instances run. Duplicates are possible only after a worker stops mid-attempt. | 3 | Integration (two workers, one DB) |
| NFR-13 | Delivered events shall be purged after a configurable retention period. | — | Integration |
| NFR-14 | The external endpoint, timeouts, retry budget, polling interval and retention shall be configurable by properties. Disabling publication shall not stop events from being recorded. | 5 | Integration |
| NFR-15 | `./mvnw clean install` with default profiles shall pass without Docker, network access or credentials, and the 274 baseline tests shall stay green. | Submission | CI / local build |

## 7. Consumer contract

These are obligations on the external system; they become `docs/integration-contract.md`.

| ID | Contract |
|---|---|
| CR-1 | Delivery is at-least-once. The same event id can arrive more than once. |
| CR-2 | The consumer acknowledges a duplicate in the same way as the first delivery. |
| CR-3 | The consumer applies an event only if its sequence is greater than the last sequence applied for that subject; otherwise it acknowledges and discards it. |
| CR-4 | The consumer reports a permanently invalid event as a permanent error, and a temporary problem as a retryable error, using the classification defined in the design. |
| CR-5 | A delete event is final for its subject. Record ids are not reused (identity columns). |
| CR-6 | Unknown fields in a payload are ignored; a breaking payload change is published under a new type version (`.v2`). |

## 8. Assumptions

| ID | Assumption | Basis |
|---|---|---|
| A1 | "Relevant changes" = create/update/delete of Owner, Pet, Visit. | Brief examples; D2 |
| A2 | One external system is the only consumer. | Brief uses the singular |
| A3 | Near-real-time is enough: seconds normally, eventual during outages. No hard latency SLA. | Brief silent |
| A4 | Every successful `save*` call produces an update event, even if no field changed. No change detection. | Simplicity; snapshot makes it harmless |
| A5 | A Pet's PetType persisted through `Pet.type` cascade (00-context §4) produces no PetType event. | D2 |
| A6 | The external system is trusted to receive owner contact data (name, address, city, telephone). | Brief silent; flagged as R6 |
| A7 | Writes outside `ClinicService` are not captured. | F1 |
| A8 | The submitted slice runs as a single instance, but the design must be safe for several (NFR-12). | Scope |
| A9 | Event time is recording time (commit is moments later), in UTC. | Simplicity |
| A10 | Security stays disabled by default; when enabled, operator endpoints require `ADMIN`. | 00-context §2 |
| A11 | Identity ids are never reused for Owner, Pet or Visit. | `GENERATED ALWAYS AS IDENTITY` in schema |

## 9. Risks

| ID | Risk | Mitigation | Covered by |
|---|---|---|---|
| R1 | Worker publishes successfully, then stops before marking Delivered → duplicate. | Accept; at-least-once + stable event id + CR-1..3. | NFR-12, CR-1 |
| R2 | Long outage grows the event table. | Indexed due-query, oldest-Pending-age metric for alerting, retention for Delivered only. | NFR-5, NFR-8, NFR-13 |
| R3 | A permanently failing event blocks its subject. | Retry budget, then Failed; later events continue. | NFR-4, NFR-7 |
| R4 | Dependants not enumerable at delete time under some profile → missed child events. | Enumerate before delete; test cascades on ≥2 profiles. | FR-2, FR-4 |
| R5 | Holding a DB transaction open during a network call exhausts connections when the external system is slow. | Design rule: no transaction across publish I/O. | NFR-1, design |
| R6 | Personal data leaves PetClinic. | Documented assumption A6; field filtering as follow-up. | A6 |
| R7 | Payload schema drift breaks the consumer. | Versioned event types; dedicated event payload classes, not REST DTOs (F5). | FR-6, CR-6 |
| R8 | AI-generated code uses Boot 3 / Jackson 2 APIs. | Review against 00-context §2; build fails on wrong packages. | NFR-15 |

## 10. Handed to design (02-design.md)

| ID | Question |
|---|---|
| OQ-1 | Transport and delivery model: push (webhook), pull (change feed), or broker. → ADR-001 |
| OQ-2 | Envelope format (e.g. CloudEvents) and exact payload fields per record type. → ADR-002 |
| OQ-3 | Retryable vs permanent error classification for the chosen transport; backoff base, cap and budget. → ADR-004 |
| OQ-4 | How the relay claims events (lease vs row locking) so that NFR-12 holds on all four databases. |
| OQ-5 | Where recording happens: explicitly in `ClinicServiceImpl`, or via a transactional domain-event listener. → ADR-005 |
| OQ-6 | Operator API shape and how it is added to the contract-first `openapi.yml`. |
| OQ-7 | Default retention period and polling interval. |

## 11. Traceability to the brief

| Brief requirement | Covered by |
|---|---|
| 1 · Relevant changes become available | FR-1, FR-2, FR-4, FR-5, FR-8, FR-9 |
| 2 · No silent loss on temporary failure | FR-3, NFR-2, NFR-3, NFR-4, NFR-5, D1 |
| 3 · Duplicates handled or documented | FR-6, NFR-6, NFR-12, CR-1..CR-3, D3 |
| 4 · Main workflow not unnecessarily fragile | NFR-1, NFR-2, NFR-11, D3 (no `@Version`) |
| 5 · Failures observable and recoverable | NFR-4, NFR-8, NFR-9, NFR-10, NFR-14 |
