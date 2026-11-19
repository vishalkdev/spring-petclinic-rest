# ADR-002 · CloudEvents with full snapshots

Status: proposed

**Context.** The consumer must cope with duplicates and late events, and should not need to call back into PetClinic.

**Decision.** CloudEvents 1.0 JSON. Each event carries the full record after the change (deletes carry id and parent id). Types are versioned, e.g. `petclinic.owner.updated.v1`. Extra fields: `seq` (integer) and `cause` (parent of a cascaded delete). Payloads use their own classes, not the generated REST DTOs. Pets also carry `typeName`, since pet type events are out of scope.

**Alternatives.**
- Id-only notifications: consumer depends on PetClinic being up.
- Field diffs: break when an event is missed or duplicated.
- Reusing REST DTOs: API changes would break the integration.

**Consequences.** Owner contact data is sent to the external system. Breaking changes go out as `.v2`.
