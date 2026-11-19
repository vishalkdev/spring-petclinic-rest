# ADR-005 · Record changes in ClinicServiceImpl

Status: proposed

**Context.** There are three repository implementations, and deletes cascade in different ways (JDBC loops, JPA cascades, bulk JPQL deletes, DB `ON DELETE CASCADE`). All writes go through transactional methods in `ClinicServiceImpl`.

**Decision.** Inject a `ChangeRecorder` into `ClinicServiceImpl` and call it explicitly in each save/delete method. Use `isNew()` before saving to tell create from update, and list child records before deleting.

**Alternatives.**
- `@TransactionalEventListener(BEFORE_COMMIT)`: workable, but more indirection for six methods.
- JPA entity listeners: do nothing under the JDBC profile and miss bulk deletes.
- AOP on repositories: hidden, and loses create-vs-update intent.

**Consequences.** One new constructor dependency. New write methods must remember to record; tests cover the existing ones.
