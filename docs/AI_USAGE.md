# AI usage

## Which AI tools I used

- **Claude (Anthropic)** in the Claude desktop app, connected to my local clone of the repository. It could read and edit files; it could not build. Every build and test run was done by me on my machine (OpenJDK 21, macOS).
- No other AI tools.

## How I used them

| Stage | What Claude did | What I did |
|---|---|---|
| Context (`00-context.md`) | Read the codebase; found the delete cascades and that the project is on Spring Boot 4 / Jackson 3 | Ran the baseline build (274 tests), reviewed the findings |
| Requirements (`01-requirements.md`) | Listed the open questions with options and trade-offs | Chose D1–D3 (cascaded deletes, scope, sequence source) after questioning each recommendation |
| Design, ADRs, tasks | Drafted the outbox design, six ADRs and the T1–T10 plan | Reviewed them; asked for the documents to be cut to short, plain versions before committing |
| Implementation (T1–T9) | Drafted code and tests per task | Built after every task, read the diffs, investigated failures with it, committed task by task |
| Verification | Mapped requirements to tests; wrote the run guide | Ran the application end to end (UI, Swagger, WireMock): outage, recovery, parking, re-queue, cascaded deletes |

The spec was committed before the code, and spec changes found during implementation are logged in `docs/sdd/CHANGELOG.md`.

## Suggestions I rejected or changed significantly, and why

**1. AI-written tests that my builds proved wrong.** I did not accept generated tests as correct until they ran on my machine.
- *T2:* the first relay tests pointed at an H2 database, but the project's test resources default to HSQLDB, so the Spring context failed to start. Fixed by matching the test profile.
- *T5:* a cascade test failed only under the `jpa` profile. Instead of loosening the assertion, we added temporary diagnostics. They showed the production code was right and the test helper was wrong: it used id 0 to mean "no events", and HSQLDB numbers identities from 0. I kept the fix in both places: the helper, and the schema (`START WITH 1`), so the event sequence starts at 1 on every database and a consumer starting from 0 cannot drop the first event.

**2. Plans that assumed APIs instead of checking them.** For T7 the first plan relied on remembered Spring Boot health classes. Boot 4 moved them to a new package, so we checked the official Spring Boot 4 API docs before writing the code.

## Issues found because of the integration

Recording events forces Hibernate to flush earlier than before. That exposed two existing bugs under the JPA profiles, which I chose to fix rather than work around, because otherwise PetClinic would publish events that do not match its database:
- **F7:** `Pet.type` used `cascade = ALL`, so deleting an owner or pet also tried to delete the shared pet type. Two upstream tests hid this by catching every exception.
- **F8:** deleted pets and visits were re-persisted through the parent's cascading collection; under the `jpa` profile a visit delete would not reach the database.

## Data handling

Only the public PetClinic code was shared with the tool. No secrets, personal data or employer information.
