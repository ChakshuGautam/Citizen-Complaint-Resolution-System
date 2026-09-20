// src/client.js
//
// Minimal JSON HTTP client for the v3 Kong. Node standard library only.
const http = require('node:http');
const https = require('node:https');

function baseUrl() {
  return process.env.V3_BASE || 'http://d3-kong:8000';
}

function timeoutMs() {
  return Number(process.env.V3_TIMEOUT_MS || 30000);
}

// call({method, path, headers, body}) -> Promise<{status, json, raw, headers}>
// v3 needs no auth today (Kong has zero plugins); identity travels as the
// X-Tenant-ID / X-User-ID headers built by src/context.js.
function call({ method = 'POST', path = '/', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    let url;
    try {
      url = new URL(path, baseUrl());
    } catch (err) {
      reject(new Error(`client.call: bad path ${JSON.stringify(path)} against ${baseUrl()}: ${err.message}`));
      return;
    }

    const payload =
      body === undefined || body === null
        ? null
        : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body));

    const outHeaders = Object.assign({ accept: 'application/json' }, headers);
    if (payload) {
      if (!Object.keys(outHeaders).some((k) => k.toLowerCase() === 'content-type')) {
        outHeaders['content-type'] = 'application/json';
      }
      outHeaders['content-length'] = payload.length;
    }

    const mod = url.protocol === 'https:' ? https : http;
    const req = mod.request(url, { method: String(method).toUpperCase(), headers: outHeaders }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let json = null;
        if (raw.length) {
          try {
            json = JSON.parse(raw);
          } catch {
            json = null; // non-JSON upstream body; caller can still read `raw`
          }
        }
        resolve({ status: res.statusCode, json, raw, headers: res.headers });
      });
    });

    req.setTimeout(timeoutMs(), () => {
      req.destroy(new Error(`client.call: timeout after ${timeoutMs()}ms calling ${url.href}`));
    });
    req.on('error', (err) => reject(new Error(`client.call: ${String(method).toUpperCase()} ${url.href}: ${err.message}`)));
    if (payload) req.write(payload);
    req.end();
  });
}

module.exports = { call, baseUrl };
