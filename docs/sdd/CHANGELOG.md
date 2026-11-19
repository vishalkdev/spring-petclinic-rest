# Spec changelog

Why the specification changed during the work. Newest first.

| Date | File | Change | Trigger |
|---|---|---|---|
| 2026-11-19 | 01-requirements.md | Initial requirements: FR-1..9, NFR-1..15, CR-1..6, A1..11, R1..8, OQ-1..7 | Step 1 |
| 2026-11-19 | 01-requirements.md | D3: sequence = event-store insertion order; JPA `@Version` rejected | Q3 from 00-context; `@Version` would change REST behaviour (optimistic-lock errors) |
| 2026-11-19 | 01-requirements.md | D2: PetType/Vet/Specialty events out of scope, except PetType delete side effects on pets and visits | Q2; schema shows Vet/Specialty deletes touch no in-scope table |
| 2026-11-19 | 01-requirements.md | D1: one delete event per cascaded pet/visit, with `cause` | Q1; F2/F3 cascades and bulk JPQL deletes bypass per-method capture |
| 2026-11-19 | 00-context.md | Initial codebase context and baseline | Step 0 |
