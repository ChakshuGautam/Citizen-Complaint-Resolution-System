// src/context.js
function toHeaders(body) {
  const ri = (body && body.RequestInfo) || {};
  const ui = ri.userInfo || {};
  return {
    'X-Tenant-ID': ui.tenantId || ri.tenantId || '',
    'X-User-ID': ui.uuid || '',
  };
}
module.exports = { toHeaders };
