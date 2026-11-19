# ADR-004 · Retries and parking

Status: proposed

**Context.** Temporary failures must not lose events, permanent ones must not retry forever, and retries must survive a restart.

**Decision.** Store `attempts` and `next_attempt_at` on the event row. Retry connection errors, timeouts, 408, 425, 429 and 5xx with exponential backoff and full jitter (2 s doubling, 10 min cap, 15 attempts). Other 4xx park the event as `FAILED` straight away. FAILED events are kept and can be re-queued through the admin API.

**Alternatives.**
- In-memory retries (`@Retryable`, Resilience4j): lost on restart and block the relay.
- Retry everything forever: hides real errors.
- Separate dead-letter table: one table with a status is simpler.

**Consequences.** With the defaults an event parks after about an hour of failures; after longer outages an operator re-queues it.
