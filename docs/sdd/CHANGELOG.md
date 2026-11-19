# Spec changelog

Why the specification changed during the work. Newest first.

| Date | File | Change | Trigger |
|---|---|---|---|
| 2026-11-19 | 00-context.md | Added F7: `Pet.type` cascade REMOVE breaks pet/owner deletes under JPA; changed to PERSIST, MERGE | T1 flush made `shouldDeleteOwner` fail on the JPA profiles; the old tests had swallowed the exception |
| 2026-11-19 | 02-design.md | Recording flushes JPA before inserting the event | Found in T1: Hibernate defers UPDATE/DELETE to commit, so without a flush D3's lock-ordering argument does not hold under the JPA profiles |
| 2026-11-19 | 00-context.md, 01-requirements.md | Condensed for readability (00 went in with the design commit); no requirement, decision or ID changed | Docs were too long to review quickly |
| 2026-11-19 | 02-design.md, ADR-001..006 | Initial design and ADRs | Step 2; answers OQ-1..OQ-7 |
| 2026-11-19 | 01-requirements.md | FR-7: pet events also carry `typeName` | Found while designing payloads: without pet type events, `typeId` alone means nothing to the consumer |
| 2026-11-19 | 01-requirements.md | Initial requirements: FR-1..9, NFR-1..15, CR-1..6, A1..11, R1..8, OQ-1..7 | Step 1 |
| 2026-11-19 | 01-requirements.md | D3: sequence = event-store insertion order; JPA `@Version` rejected | Q3 from 00-context; `@Version` would change REST behaviour (optimistic-lock errors) |
| 2026-11-19 | 01-requirements.md | D2: PetType/Vet/Specialty events out of scope, except PetType delete side effects on pets and visits | Q2; schema shows Vet/Specialty deletes touch no in-scope table |
| 2026-11-19 | 01-requirements.md | D1: one delete event per cascaded pet/visit, with `cause` | Q1; F2/F3 cascades and bulk JPQL deletes bypass per-method capture |
| 2026-11-19 | 00-context.md | Initial codebase context and baseline | Step 0 |
