# 04 · Verification

How each requirement is shown to work. All tests run with `./mvnw clean install`; no Docker, network or credentials are needed.

**Result (2026-11-19):** 376 tests, 0 failures (274 baseline + 102 new). The baseline tests still pass.

Recording tests run under four profiles (H2 JDBC, HSQLDB JDBC, JPA, Spring Data JPA) through `ChangeRecording*Tests` and `CascadeRecording*Tests`.

| Requirement | Tests |
|---|---|
| FR-1 record each change | `AbstractChangeRecordingTests`: `recordsCreatedEventWhenOwnerIsCreated`, `recordsUpdatedEventWithNewStateWhenOwnerIsUpdated`, `recordsDeletedEventWhenOwnerIsDeleted`, `recordsPetCreatedAndUpdatedEvents`, `recordsPetDeletedEvent`, `recordsVisitCreatedUpdatedAndDeletedEvents`, `deletingAnExistingVisitRecordsOneEvent` |
| FR-2 cascaded deletes | `AbstractCascadeRecordingTests`: `deletingAnOwnerRecordsItsPetsAndVisitsFirst`, `deletingAPetRecordsItsVisitsFirst`, `deletingAPetTypeRecordsEveryPetAndVisitItRemoves` |
| FR-3 nothing on rollback | `recordsNothingWhenTheTransactionRollsBack` |
| FR-4 every profile | All recording tests × 4 profiles |
| FR-5, FR-7 payloads | payload assertions in the recording tests (incl. `typeName`, ISO dates) |
| FR-6 envelope | `RelayDeliveryTests.deliversAPendingEventAsACloudEvent` |
| FR-8 async delivery | `deliversAPendingEventAsACloudEvent`, `ScheduledRelayTests.deliversCommittedChangesOnItsOwn` |
| FR-9 not resent once delivered | `doesNotSendAnEventAgainOnceItIsDelivered` |
| NFR-1 requests unaffected | `RestIsolationTests.createsOwnersNormallyWhileTheExternalSystemHangs` |
| NFR-2 local insert in business TX | `recordsNothingWhenTheTransactionRollsBack`, `recorderRefusesToRunOutsideATransaction` |
| NFR-3 retry with backoff, survives restart | `RelayFailureHandlingTests`: `retriesWithBackoffUntilTheExternalSystemRecovers`, `retriesAfterATimeout`, `keepsTheRetryScheduleAcrossARestart`, `waitsAsLongAsTheExternalSystemAsksWithRetryAfter`; `BackoffPolicyTests`; `HttpResponseClassifierTests` |
| NFR-4 park on permanent error / exhausted retries | `parksTheEventAtOnceOnAPermanentError`, `parksTheEventWhenRetriesRunOut` |
| NFR-5 undelivered never deleted | `RetentionJobTests.purgesOnlyDeliveredEventsOlderThanTheRetentionPeriod` |
| NFR-6 order per record | `RelayOrderingTests.holdsLaterEventsForARecordWhileAnEarlierOneIsRetrying` |
| NFR-7 FAILED does not block | `doesNotLetAFailedEventBlockLaterEventsForTheSameRecord` |
| NFR-8 metrics | `OutboxObservabilityTests.exposesTheBacklogAndPublishOutcomesAsMetrics` |
| NFR-9 logs | `logsEveryAttemptWithTheEventId` |
| NFR-10 operator recovery | `OutboxAdminTests` (5 tests, incl. delivery with the same event id after re-queue) |
| NFR-11 health stays UP | `healthStaysUpAndShowsTheBacklog` |
| NFR-12 one worker per event | `leavesAnEventAloneWhileAnotherWorkerHoldsItsLease`, `twoRelaysNeverSendTheSameEventTwice` |
| NFR-13 retention | `RetentionJobTests` |
| NFR-14 configuration | `doesNotScheduleTheRelayWhenPublishingIsDisabled`, `ScheduledRelayTests` |
| NFR-15 build without Docker | the full build above |
| CR-1..CR-6 | documented in `docs/integration-contract.md` |

## Manual check

Run the app with publishing on and a stub receiver (see README), create an owner with `POST /petclinic/api/owners`, and watch the receiver get a `petclinic.owner.created.v1` event. Stop the receiver, create another owner, and check `GET /petclinic/api/admin/outbox/events?status=PENDING` and `/petclinic/actuator/health`.

**Manual run (2026-11-19):** UI (spring-petclinic-angular) + Swagger UI + WireMock, steps 1–8 of the README run guide. Creates, updates and deletes reached the stub in order; during a WireMock outage the UI kept saving and events stayed PENDING until it returned. Deviation seen: after a WireMock restart with no mappings it answered 404, so two events were parked as FAILED (404 is classed as permanent); `retry-failed` re-queued them and they were delivered with their original event ids.

## Not verified

- MySQL and Postgres schemas (no Docker in the default build); the SQL is portable but untested there.
- The `jpa`-profile visit-delete bug (F8) was inferred from tests, not reproduced against the running app.
