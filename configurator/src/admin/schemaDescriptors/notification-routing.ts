// Schema is `additionalProperties: false`; keep this descriptor in sync with
// utilities/default-data-handler/.../schema/RAINMAKER-PGR.json (NotificationRouting).

import type { SchemaDescriptor } from './types';

/**
 * Descriptor for `RAINMAKER-PGR.NotificationRouting` — config-driven "who is
 * notified" per workflow transition. One record per (businessService, action,
 * toState). Flat scalar key fields + two short enum arrays (subscribers,
 * channels) rendered as chip editors, so the generic form handles it with no
 * custom editor.
 */
export const notificationRoutingDescriptor: SchemaDescriptor = {
  schema: 'RAINMAKER-PGR.NotificationRouting',
  groups: [
    { title: 'Transition', fields: ['businessService', 'fromState', 'action', 'toState'] },
    { title: 'Routing', fields: ['subscribers', 'channels', 'active'] },
  ],
  fields: [
    { path: 'businessService', required: true, label: 'Business Service', help: 'Workflow business service, e.g. PGR.' },
    { path: 'fromState', label: 'From State', help: 'Documentation/UI only — runtime matches on action + toState (the consumer lacks fromState). Leave blank for "any".' },
    { path: 'action', required: true, label: 'Action', help: 'Workflow action, e.g. ASSIGN, REASSIGN, REJECT, RESOLVE, REOPEN, RATE, APPLY.' },
    { path: 'toState', required: true, label: 'To State', help: 'Resulting status, e.g. PENDINGATLME. Disambiguates same-action transitions (RATE -> CLOSEDAFTERRESOLUTION vs CLOSEDAFTERREJECTION).' },
    { path: 'subscribers', widget: 'chip-array', required: true, label: 'Subscribers', help: 'Relationship codes (NOT RBAC roles): CITIZEN, ASSIGNEE, CREATOR, PREVIOUS_ASSIGNEE.' },
    { path: 'channels', widget: 'chip-array', required: true, label: 'Channels', help: 'SMS, WHATSAPP, EMAIL.' },
    { path: 'active', widget: 'boolean', label: 'Active' },
  ],
};
