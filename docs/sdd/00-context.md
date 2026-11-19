# 00 · Codebase context

What the existing code does, checked before writing requirements. Fork point: upstream `0515ec8`.

## Baseline build

`./mvnw clean install` on 2026-11-19: **BUILD SUCCESS**, 274 tests, 0 failures, 55.8 s.
OpenJDK 21.0.11 (Microsoft build), macOS 26.5.2, MacBook Pro M2. Default profiles `h2,spring-data-jpa`.

## Key facts

- Spring Boot 4.1.2 with Jackson 3 (`tools.jackson`), not Jackson 2.
- REST is contract-first: `openapi.yml` generates DTOs and API interfaces; MapStruct maps them.
- Three repository implementations (`jdbc`, `jpa`, `springdatajpa`), chosen by profile.
- Schema comes from `db/<h2|hsqldb|mysql|postgres>/schema.sql`; no Flyway.
- Security is off by default; roles are `OWNER_ADMIN`, `VET_ADMIN`, `ADMIN`.
- Entities have no `@Version`. `isNew()` means `id == null`.

## Write paths

All writes go through `@Transactional` methods in `ClinicServiceImpl`. The v2 controllers are read-only.

| Methods | Records |
|---|---|
| `saveOwner`, `deleteOwner` | Owner |
| `savePet`, `deletePet` | Pet |
| `saveVisit`, `deleteVisit` | Visit |
| `save/deletePetType`, `save/deleteVet`, `save/deleteSpecialty` | Reference data |

## Hidden deletes

- Deleting an **owner** also deletes their pets and visits.
- Deleting a **pet** also deletes its visits.
- Deleting a **pet type** deletes every pet of that type and their visits (bulk JPQL `DELETE` in the JPA repositories, `ON DELETE CASCADE` in the DB).
- Saving a pet can also save its pet type (`cascade = ALL` on `Pet.type`).

## Findings

- **F1** `ClinicServiceImpl` is the one place every write passes through, for every profile.
- **F2** Child records removed by a cascade never pass through `deletePet` / `deleteVisit`.
- **F3** A pet type delete (reference data) removes pets and visits.
- **F4** There is no version column to order events by.
- **F5** The generated DTOs are the REST contract, not an integration contract.
- **F6** A new table means editing four schema files.
- **F7** *(found in T1)* `Pet.type` used `cascade = ALL`, so under JPA deleting a pet or owner also tried to delete the shared pet type and failed. Two existing tests hid this by catching the exception.

## Questions for requirements

- **Q1** How should cascaded deletes be reported?
- **Q2** Are pet types, vets and specialties in scope?
- **Q3** What gives events their order?
