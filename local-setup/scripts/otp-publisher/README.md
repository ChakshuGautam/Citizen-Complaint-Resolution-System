# OTP → Novu wiring (DRAFT)

## Current state

`/user-otp/v1/_send` is terminated by Kong's `request-termination`
plugin and returns a mock 200 — no SMS is sent. Same for
`/otp/v1/_validate`. The SPA's citizen-login flow uses a fixed OTP
(`123456`) for both production and dev.

## What we need

Real OTPs delivered to the citizen's phone via the Novu+Twilio stack
that landed in PR #36.

## Two approaches, pick one

### A. Modify Kong to forward `/user-otp` to a small publisher service

1. Remove the `request-termination` plugin from `/user-otp` upstream.
2. Add a new tiny service `otp-publisher` (Node, Python, whatever) that:
   - Accepts `POST /user-otp/v1/_send`
   - Generates a 6-digit OTP
   - Caches `(otp, mobileNumber)` in Redis with 10-min TTL
   - Publishes to Kafka topic `complaints.domain.events` with envelope
     `{"eventName":"OTP.SEND","payload":{"otp":"...","phone":"..."}}`
   - Returns 200 (no body, like the original mock)
3. novu-bridge consumes the event, looks up the OTP TemplateBinding
   (seeded by PR #37), triggers Novu's `otp-send` workflow → SMS lands.
4. For `_validate`, the publisher reads the cache and confirms.

### B. Modify egov-user to publish OTP events

1. egov-user already generates OTPs internally (it has the auth flow).
2. Add a Kafka producer that publishes after OTP generation.
3. Replace Kong's termination with a real proxy to egov-user.

A is faster to ship (~half day). B is cleaner long-term but invasive
(changes a core service).

## Files in this draft

None yet — this is a scaffold. Pick an approach, then:

- For A: write `otp-publisher/server.js` + Dockerfile + compose entry +
  Kong config change.
- For B: open a follow-up in `backend/` to add the egov-user producer.

## Acceptance criteria

```bash
# A citizen on the SPA:
1. Enters phone number
2. Receives a real SMS with a 6-digit code on their phone
3. Enters the code
4. Logs in successfully
```
