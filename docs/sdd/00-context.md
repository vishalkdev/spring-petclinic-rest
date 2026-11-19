# 00 · Codebase context and baseline

Status: draft · Step 0 of the SDD process
Purpose: record what the existing code actually does before any requirement or design decision.
Everything in `01-requirements.md` and later should trace back to a fact in this file or to the assignment brief.

## 1. Baseline build

Upstream commit at fork point: `0515ec8` (Add name validation patterns for Specialty, PetType and Role, #396)

| Item | Result |
|---|---|
| Command | `./mvnw clean install` |
| Date | 2026-11-19 |
| JDK (`java -version`) | OpenJDK 21.0.11 LTS (Microsoft build 21.0.11+10-LTS) |
| OS | macOS 26.5.2, MacBook Pro (Apple M2) |
| Result | BUILD SUCCESS |
| Tests run / failures / skipped | 274 / 0 / 0 (errors: 0) |
| Duration | 55.8 s |
| Notes | Default profiles (h2, spring-data-jpa). No failures; this is the regression baseline every later change must keep green. |

## 2. Stack facts that constrain the design

| Fact | Where | Why it matters |
|---|---|---|
| Spring Boot **4.1.2** (Spring Framework 7); project version 4.0.2 | `pom.xml` parent | `RestClient` is available. AI tools trained on Boot 3 may suggest outdated APIs. |
| Jackson **3** (`tools.jackson.*`) | `pom.xml` | Serialisation code must use Jackson 3 packages, not `com.fasterxml.jackson.databind`. |
| Contract-first REST: `openapi.yml` → OpenAPI Generator DTOs + API interfaces; MapStruct 1.6.3 mappers | `src/main/resources/openapi.yml`, `target/generated-sources` | New endpoints (e.g. admin/replay) are added to `openapi.yml`, not hand-written. Generated DTOs are the REST contract, not an integration contract. |
| Default profiles `h2,spring-data-jpa` | `application.properties` | Default build and tests run on H2 + Spring Data JPA. |
| Schema created by `spring.sql.init` from `db/<platform>/schema.sql`; platforms h2, hsqldb, mysql, postgres | `application.properties`, `src/main/resources/db/*` | A new table must be added to all four schema scripts. No Flyway/Liquibase. |
| Three repository implementations: `jdbc`, `jpa`, `springdatajpa`, chosen by profile | `repository/*` | Change capture must not depend on one persistence technology. |
| Security off by default (`petclinic.security.enable=false`); roles `OWNER_ADMIN`, `VET_ADMIN`, `ADMIN` | `application.properties`, security config | Any operator endpoint should be guarded by `ADMIN` when security is on. |
| No optimistic-lock `@Version` on entities; `BaseEntity.isNew()` is `id == null` | `model/BaseEntity.java` | No per-aggregate version exists today. Create vs update can be told apart before save. |
| JaCoCo and Checkstyle plugins configured | `pom.xml` | New code must pass the existing build checks. |

## 3. Write paths

All writes go through `ClinicService` → `ClinicServiceImpl`. Every write method there is annotated `@Transactional`.
The v2 controllers (`OwnerRestControllerV2`, `PetRestControllerV2`) are read-only (paged listing).

| Service method | Called from (v1 controllers) | Aggregate | Candidate for integration |
|---|---|---|---|
| `saveOwner` | `OwnerRestControllerV1` (create, update) | Owner | yes |
| `deleteOwner` | `OwnerRestControllerV1` | Owner (+ pets, visits) | yes |
| `savePet` | `OwnerRestControllerV1` (add pet, update owner's pet), `PetRestControllerV1` (update) | Pet | yes |
| `deletePet` | `PetRestControllerV1` | Pet (+ visits) | yes |
| `saveVisit` | `OwnerRestControllerV1` (add visit), `VisitRestControllerV1` (create, update) | Visit | yes |
| `deleteVisit` | `VisitRestControllerV1` | Visit | yes |
| `savePetType` / `deletePetType` | `PetTypeRestControllerV1` | PetType (+ pets, visits on delete) | reference data; see F2 |
| `saveVet` / `deleteVet` | `VetRestControllerV1` | Vet | reference data |
| `saveSpecialty` / `deleteSpecialty` | `SpecialtyRestControllerV1` | Specialty | reference data |

## 4. Hidden side effects (cascades)

| Operation | What actually gets deleted | Mechanism by profile |
|---|---|---|
| `deleteOwner` | owner, all their pets, all visits of those pets | JDBC: explicit loop deletes visits, then pets, then owner. JPA / Spring Data JPA: `CascadeType.ALL` on `Owner.pets` and `Pet.visits`. DB: `ON DELETE CASCADE` on `pets.owner_id`. |
| `deletePet` | pet and its visits | JDBC: explicit loop. JPA: `CascadeType.ALL` on `Pet.visits`. DB: `ON DELETE CASCADE` on `visits.pet_id`. |
| `deletePetType` | pet type, **every pet of that type**, and their visits | JPA and Spring Data JPA: bulk JPQL `DELETE` statements per visit/pet (bypass entity lifecycle). DB: `ON DELETE CASCADE` on `pets.type_id`. |
| `savePet` | may also persist/merge the pet's `PetType` | `@ManyToOne(cascade = CascadeType.ALL)` on `Pet.type` |

## 5. Findings that shape the integration

- **F1 · Single capture point.** `ClinicServiceImpl` is the only write path for all three persistence implementations and is already transactional. Recording changes there works for every profile; JPA entity listeners would not fire under the JDBC profile, and would not see the bulk JPQL deletes.
- **F2 · Cascaded deletes are invisible to per-method capture.** Deleting an owner, a pet or a pet type removes child pets/visits without calling `deletePet` / `deleteVisit`. If only the called method emits an event, the external system keeps stale pets and visits. This is a direct risk to requirement 2 ("not lost without notice") and needs an explicit decision in `01-requirements.md`.
- **F3 · Reference data can delete clinical data.** `deletePetType` is a vet-admin action on reference data but deletes owners' pets and visits. Even if PetType is out of scope, its side effects on Pet and Visit are in scope.
- **F4 · No version column.** Ordering and stale-update detection need a sequence source; none exists on the entities.
- **F5 · Generated DTOs are the REST contract.** Reusing them as event payloads would couple the integration contract to API changes.
- **F6 · Schema management is script-based.** Adding a table means editing four `schema.sql` files; introducing a migration tool would be an unrelated change.

## 6. Open questions raised here (resolved in 01-requirements.md)

- Q1 · How are cascaded child deletes communicated: one event per deleted child, or one parent event that states its children were removed?
- Q2 · Is PetType / Vet / Specialty in scope as their own event streams, or only through their side effects (F3)?
- Q3 · What provides the event sequence, given F4?

## 7. Not yet verified

- Behaviour of the `jdbc` profile build/tests on this machine.
- Whether `Owner` → `Pet` loading is eager enough at delete time to enumerate children for every profile (needed if Q1 = one event per child).
