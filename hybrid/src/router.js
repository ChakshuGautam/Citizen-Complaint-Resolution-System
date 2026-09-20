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

function register(pathPrefix, translator) {
  if (typeof pathPrefix !== 'string' || !pathPrefix.startsWith('/')) {
    throw new TypeError(`router.register: pathPrefix must start with "/" (got ${JSON.stringify(pathPrefix)})`);
  }
  if (!translator || typeof translator.toV3 !== 'function' || typeof translator.toV2 !== 'function') {
    throw new TypeError(`router.register: translator for ${pathPrefix} must expose toV3() and toV2()`);
  }
  routes.set(normalize(pathPrefix), translator);
  return translator;
}

// Longest registered prefix wins; a prefix only matches on a path-segment
// boundary, so '/egov-idgen' does not match '/egov-idgenerator/...'.
function resolve(path) {
  if (typeof path !== 'string') return null;
  const p = normalize(path);
  let best = null;
  let bestLen = -1;
  for (const [prefix, translator] of routes) {
    const isMatch = p === prefix || p.startsWith(prefix === '/' ? '/' : prefix + '/');
    if (isMatch && prefix.length > bestLen) {
      best = translator;
      bestLen = prefix.length;
    }
  }
  return best;
}

function registered() {
  return [...routes.keys()].sort();
}

function reset() {
  routes.clear();
}

module.exports = { register, resolve, registered, reset };
