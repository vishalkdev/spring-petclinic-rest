# 03 · Tasks

Status: draft.

**Rules.** Each task keeps `./mvnw clean install` green (274 baseline tests), ships its tests with the code, and uses commit messages like `T1: ... [FR-1]`. If a task shows a spec problem, update the spec and the CHANGELOG first.

**Order.** Owner end to end first (T1–T3) to prove the core reliability path early, then the other records, then the operational features.

| # | Task | Done when (tests) | Requirements |
|---|---|---|---|
| T1 | Event table in 4 schemas, `OutboxRepository`, `ChangeRecorder`, Owner payload; record owner create/update/delete | Recording test on all 4 profiles: one row per change, correct type and payload; rollback leaves no row | FR-1 (owner), FR-3, FR-4, FR-5, FR-6, NFR-2 |
| T2 | Properties, relay (`runOnce` + `@Scheduled`), lease claim, HTTP publisher | WireMock receives the CloudEvent with `Idempotency-Key`; row becomes DELIVERED; with publishing off, events are still recorded | FR-8, FR-9, NFR-14 |
| T3 | Error classification, backoff, retry, parking | 503, 503, 200 delivers on attempt 3; timeout retries; 400 parks at once; max attempts parks; pending events survive a restart; `POST /owners` returns 201 while the stub is down | NFR-1, NFR-3, NFR-4 |
| T4 | Pet and Visit recording | Create/update/delete events with correct payloads; pet carries `typeName` | FR-1, FR-5, FR-7 |
| T5 | Cascaded deletes (owner, pet, pet type) | Child delete events emitted before the parent, with `cause`; tested on 2+ profiles | FR-2 |
| T6 | Per-record ordering and two-worker safety | Two updates to one owner arrive in order when the first is retried; a FAILED event does not block later ones; two relays never send the same event at once | NFR-6, NFR-7, NFR-12 |
| T7 | Metrics, logs, health | Gauges show pending/failed/oldest age; health stays UP with a backlog | NFR-8, NFR-9, NFR-11 |
| T8 | Admin API in `openapi.yml` + controller | List FAILED; re-queue one and all; re-queued event is delivered; 404/409 cases | NFR-10 |
| T9 | Retention job | Old DELIVERED rows deleted; PENDING and FAILED never deleted | NFR-5, NFR-13 |
| T10 | Docs: `integration-contract.md`, README section, `APPROACH.md`, `AI_USAGE.md`, `04-verification.md` | Every requirement mapped to a test; build runs without Docker | CR-1..CR-6, NFR-15 |

## Progress

- [x] T1
- [x] T2
- [x] T3
- [x] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8
- [ ] T9
- [ ] T10
