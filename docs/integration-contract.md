# Integration contract (for the external system)

PetClinic sends one HTTP request per change to Owners, Pets and Visits.

## Request

`POST {petclinic.integration.publisher.endpoint}`

| Header | Value |
|---|---|
| `Content-Type` | `application/cloudevents+json` |
| `Idempotency-Key` | the event `id` (same on every retry) |
| `Authorization` | `Bearer <token>` if `publisher.auth-token` is set |

Body: a CloudEvents 1.0 event.

```json
{
  "specversion": "1.0",
  "id": "6f1c2e0a-8a1b-4c7e-9d0f-2a4b6c8d0b91",
  "source": "/petclinic",
  "type": "petclinic.pet.deleted.v1",
  "subject": "pet/8",
  "time": "2026-11-19T04:12:44.120Z",
  "datacontenttype": "application/json",
  "seq": 1042,
  "cause": "owner/6",
  "data": { "id": 8, "ownerId": 6 }
}
```

| Type | `data` |
|---|---|
| `petclinic.owner.created.v1` / `updated.v1` | `id, firstName, lastName, address, city, telephone` |
| `petclinic.owner.deleted.v1` | `id` |
| `petclinic.pet.created.v1` / `updated.v1` | `id, name, birthDate, ownerId, typeId, typeName` |
| `petclinic.pet.deleted.v1` | `id, ownerId` |
| `petclinic.visit.created.v1` / `updated.v1` | `id, petId, date, description` |
| `petclinic.visit.deleted.v1` | `id, petId` |

Dates are ISO strings (`2020-01-02`). `cause` is present only on deletes caused by deleting a parent (owner, pet or pet type).

## What the consumer must do

1. **Expect duplicates** (CR-1). Delivery is at-least-once.
2. **Answer a duplicate like the first delivery** (CR-2), with 2xx.
3. **Apply an event only if its `seq` is higher than the last one you applied for that `subject`** (CR-3). Otherwise acknowledge and ignore it. Events for one subject arrive in order, but an old event can come back after an operator re-queues it.
4. **Use 4xx only for events that can never be accepted** (CR-4). 408, 425, 429 and 5xx are retried with backoff; other 4xx park the event for an operator.
5. **Treat a delete as final** (CR-5). Ids are not reused.
6. **Ignore unknown fields** (CR-6). A breaking change will use a new type, e.g. `.v2`.

## What PetClinic guarantees

- Every committed change to an owner, pet or visit is sent at least once, unless an operator leaves it parked.
- Events for the same subject are sent in `seq` order.
- `seq` starts at 1 and increases. It shows that an event is stale; it does not show that one is missing.
