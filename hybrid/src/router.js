// src/router.js
//
// Longest-prefix router over the inbound 2.x request path.
//
// A translator is:
//   { toV3(req2x) -> {method, path, body},
//     toV2(v3Response, req2x) -> object }
const routes = new Map();

function normalize(p) {
  const bare = String(p).split('?')[0];
  return bare.length > 1 && bare.endsWith('/') ? bare.slice(0, -1) : bare;
}

// Registration is a startup-time operation, so a duplicate prefix throws:
// crashing at boot beats a silent mis-route where one dependency quietly
// starts behaving like another. Note that '/foo' and '/foo/' normalize to the
// same key, so the two spellings collide with each other too.
// Tests that need a clean slate call reset().
function register(pathPrefix, translator) {
  if (typeof pathPrefix !== 'string' || !pathPrefix.startsWith('/')) {
    throw new TypeError(`router.register: pathPrefix must start with "/" (got ${JSON.stringify(pathPrefix)})`);
  }
  if (!translator || typeof translator.toV3 !== 'function' || typeof translator.toV2 !== 'function') {
    throw new TypeError(`router.register: translator for ${pathPrefix} must expose toV3() and toV2()`);
  }
  const key = normalize(pathPrefix);
  if (routes.has(key)) {
    throw new Error(
      `router.register: ${key} is already registered` +
        (key === pathPrefix ? '' : ` (${JSON.stringify(pathPrefix)} normalizes to ${key})`) +
        ' - refusing to overwrite the existing translator'
    );
  }
  routes.set(key, translator);
  return translator;
}

// Longest registered prefix wins; a prefix only matches on a path-segment
// boundary, so '/egov-idgen' does not match '/egov-idgenerator/...'.
// resolveEntry also hands back the prefix that matched, so a caller can name
// the dependency in a gap-log entry.
function resolveEntry(path) {
  if (typeof path !== 'string') return null;
  const p = normalize(path);
  let best = null;
  let bestLen = -1;
  for (const [prefix, translator] of routes) {
    const isMatch = p === prefix || p.startsWith(prefix === '/' ? '/' : prefix + '/');
    if (isMatch && prefix.length > bestLen) {
      best = { prefix, translator };
      bestLen = prefix.length;
    }
  }
  return best;
}

function resolve(path) {
  const match = resolveEntry(path);
  return match ? match.translator : null;
}

function registered() {
  return [...routes.keys()].sort();
}

function reset() {
  routes.clear();
}

module.exports = { register, resolve, resolveEntry, registered, reset };
