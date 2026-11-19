# 01 · Requirements

From the brief and `00-context.md`. Requirements say *what*, not *how*; the transport is chosen in `02-design.md`.

## Scope

- **In:** create, update and delete of Owner, Pet and Visit, including pets and visits removed by a cascade.
- **Out:** events for pet types, vets and specialties; multiple consumers; writes that skip `ClinicService`.
- **Not a goal:** exactly-once delivery, or global ordering across records.

## Decisions

- **D1 Cascaded deletes (Q1).** One delete event per removed record, with `cause` = the parent. *Rejected:* one parent event listing children (the consumer would need to know our cascade rules); parent only (pet type deletes would lose pets and visits silently).
- **D2 Reference data (Q2).** No events of their own. A pet type delete still produces pet and visit delete events. Vet and specialty deletes touch no in-scope table (`visits` has no vet column).
- **D3 Order (Q3).** Use the event table's auto-increment id as the sequence. Updates to the same record wait on its row lock, so they get increasing ids. *Rejected:* `@Version` on entities (changes schemas and adds new REST errors). Limits: ordered per record only; numbers have gaps.

## Functional

| ID | Requirement |
|---|---|
| FR-1 | When an Owner, Pet or Visit is created, updated or deleted, record one event in the same transaction. |
| FR-2 | When a delete removes child pets or visits, record a delete event for each, with `cause`. |
| FR-3 | If the transaction rolls back, record nothing. |
| FR-4 | Works under the `jdbc`, `jpa` and `spring-data-jpa` profiles. |
| FR-5 | Create/update events carry the full record; delete events carry the id and parent id. |
| FR-6 | Each event has: id, versioned type, subject, sequence, time, payload, `cause` if any. |
| FR-7 | Related records are referenced by id. A pet also carries `typeName`, since pet types have no events. |
| FR-8 | Every recorded event is published asynchronously, at least once, unless it is FAILED. |
| FR-9 | An acknowledged event is marked DELIVERED and not sent again. |

## Reliability and operations

| ID | Requirement |
|---|---|
| NFR-1 | When the external system is down or slow, REST endpoints behave as before; requests never call it. |
| NFR-2 | Recording is a local DB insert in the business transaction. |
| NFR-3 | Retryable failures retry with backoff and jitter; the schedule survives a restart. |
| NFR-4 | Permanent failures, or running out of retries, mark the event FAILED, keep it, and log an error. |
| NFR-5 | Undelivered events are never deleted. |
| NFR-6 | Events for one record go out in order; a later one waits while an earlier one is retrying. |
| NFR-7 | A FAILED event does not block later events for the same record. |
| NFR-8 | Metrics: pending count, failed count, oldest pending age, attempts by outcome. |
| NFR-9 | Each attempt is logged with event id, subject, attempt and outcome. |
| NFR-10 | An operator can list FAILED events and re-queue one or all without a restart. |
| NFR-11 | A backlog never makes the app report DOWN. |
| NFR-12 | Only one worker sends a given event at a time, even with several instances. |
| NFR-13 | DELIVERED events are purged after a set period. |
| NFR-14 | Endpoint, timeouts, retries, polling and retention are configurable; turning publishing off still records events. |
| NFR-15 | The default build passes without Docker or network, and the 274 baseline tests stay green. |

## Consumer contract

- **CR-1** The same event may arrive more than once.
- **CR-2** Acknowledge a duplicate like the first delivery.
- **CR-3** Apply an event only if its `seq` is higher than the last applied for that subject.
- **CR-4** Use 4xx only for events that will never be valid; use 5xx for temporary problems.
- **CR-5** A delete is final; ids are not reused.
- **CR-6** Ignore unknown fields; breaking changes come as a new `.v2` type.

## Assumptions

- **A1** Relevant changes are Owner, Pet and Visit create/update/delete.
- **A2** There is one external system.
- **A3** A few seconds of delay is fine; no latency SLA.
- **A4** Every save emits an update, even with no field changes.
- **A5** A pet type saved through `Pet.type` produces no event.
- **A6** The external system may receive owner contact details.
- **A7** Writes that skip `ClinicService` are not captured.
- **A8** One instance for the demo; the design must still be safe with several.
- **A9** Event time is recording time, in UTC.
- **A10** Admin endpoints need `ADMIN` when security is on.
- **A11** Record ids are never reused.

## Risks

- **R1** Crash after sending, before marking delivered → duplicate. Accepted (CR-1).
- **R2** Long outage grows the table. Index it, alert on oldest pending age.
- **R3** One bad event blocks its record. Park it after the retry limit (NFR-7).
- **R4** Cascaded children missed under some profile. Test on several profiles.
- **R5** Holding a transaction during the HTTP call. Not allowed by design.
- **R6** Personal data leaves PetClinic. Documented (A6).
- **R7** Payload changes break the consumer. Versioned types, own payload classes.
- **R8** AI suggests Boot 3 / Jackson 2 code. Check against 00-context.

## Open questions for design

- **OQ-1** Transport: push, pull or broker?
- **OQ-2** Envelope and payload fields.
- **OQ-3** Which errors are retryable; backoff values.
- **OQ-4** How the relay claims events.
- **OQ-5** Where in the code changes are recorded.
- **OQ-6** Shape of the admin API.
- **OQ-7** Default polling and retention.
