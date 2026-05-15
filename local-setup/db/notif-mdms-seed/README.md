# Notification MDMS seed

Seed records that wire the **PGR complaint lifecycle events** to the Novu
workflows defined in `backend/novu-bridge-endpoint/workflows.js`.

The Java `novu-bridge` service consumes Kafka events from
`complaints.domain.events` and looks up two MDMS records before
dispatching:

1. **`TemplateBinding`** — keyed by `(tenantId, eventName, channel, locale)`
   → tells the bridge which Novu workflow to trigger and which payload
   variables to extract.
2. **`ProviderDetail`** — keyed by `(tenantId, providerName, channel)` →
   holds the Twilio account SID + auth token to inject as overrides.

Without these records the bridge falls back to "no template found" and
drops the event (visible in `nb_dispatch_log.status = SKIPPED`).

## Files

| File | Purpose |
|---|---|
| `schemas/TemplateBinding.json` | MDMS v2 schema definition |
| `schemas/ProviderDetail.json`  | MDMS v2 schema definition |
| `data/template-bindings.json`  | Seed: 6 events × 1 locale |
| `data/provider-details.json`   | Seed: per-tenant Twilio credentials |
| `seed.sh`                       | Idempotent seeder — POSTs schemas + data via MDMS v2 API |

## Wiring at deploy time

The Ansible playbook calls `seed.sh` once per tenant after MCP
`tenant_bootstrap` succeeds and the operator has populated their Twilio
creds (`twilio_account_sid` etc. in host_vars). The seed is idempotent
— re-runs just no-op on existing records.

## Open issue: Twilio Content templates

The upstream `TwilioProviderStrategy` hardcodes a WhatsApp Business
flow that uses Twilio's Content API (`contentSid`). For SMS without a
registered Content Template, the strategy still works — it falls back
to a plain `body` override that the Bridge endpoint renders.

For production WhatsApp delivery, the operator must:
1. Register a Content Template via Twilio Console / Content API.
2. Get the `HX...` content SID.
3. Update `data/template-bindings.json` with `contentSid` and
   `paramOrder` matching the template's variable positions.

For SMS testing (trial accounts), the `contentSid` field stays empty
and the Bridge endpoint renders the body from
`backend/novu-bridge-endpoint/workflows.js`.
