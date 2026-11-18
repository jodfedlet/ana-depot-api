#!/usr/bin/env node
/**
 * Credential-free hosting/security probe for a deployed Ana Dépôt (staging). It sends ordinary public requests only and prints PASS / FAIL / SKIP per check.
 * It proves what the DEPLOYED responses look like (TLS, headers, CORS, caching, error bodies, secrets in the public bundle); it does not replace the
 * authenticated journeys (docs/staging/README.md) and it is not a penetration test.
 *
 *   node dev/staging/probe.mjs --web https://<staging-web> --api https://<staging-api> [--rate-limit] [--local]
 *
 *   --local        the target is a plain-http local stack: TLS and HSTS checks are SKIPPED (never reported as passed)
 *   --rate-limit   also hammers POST /reservations/lookup until a 429 appears (changes only rate-limit counters, but run it deliberately)
 */
import tls from "node:tls";

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(`--${name}`); return i >= 0 ? args[i + 1] : undefined; };
const WEB = (opt("web") ?? "").replace(/\/+$/, "");
const API = (opt("api") ?? "").replace(/\/+$/, "");
const LOCAL = args.includes("--local");
if (!WEB || !API) { console.error("usage: node dev/staging/probe.mjs --web <url> --api <url> [--rate-limit] [--local]"); process.exit(2); }

let failed = 0;
const row = (status, name, extra = "") => { if (status === "FAIL") failed++; console.log(`${status.padEnd(4)}  ${name}${extra ? `  [${String(extra).replace(/\s+/g, " ").slice(0, 180)}]` : ""}`); };
const check = (name, ok, extra) => row(ok ? "PASS" : "FAIL", name, extra);
const skip = (name, why) => row("SKIP", name, why);
const get = (url, init = {}) => fetch(url, { redirect: "manual", signal: AbortSignal.timeout(20000), ...init });
const hdr = (r, k) => r.headers.get(k) ?? "";

// ---- TLS ----------------------------------------------------------------------------------------------------------------------
async function tlsInfo(base) {
  const u = new URL(base);
  return new Promise((resolve, reject) => {
    const s = tls.connect({ host: u.hostname, port: Number(u.port || 443), servername: u.hostname, rejectUnauthorized: true }, () => {
      const c = s.getPeerCertificate();
      resolve({ protocol: s.getProtocol(), validTo: new Date(c.valid_to), subject: c.subject?.CN, authorized: s.authorized });
      s.end();
    });
    s.on("error", reject);
    s.setTimeout(15000, () => { s.destroy(); reject(new Error("tls timeout")); });
  });
}
console.log(`\n== HTTPS and certificates (${LOCAL ? "local: skipped" : "hosted"})`);
for (const [label, base] of [["web", WEB], ["api", API]]) {
  if (LOCAL) { skip(`${label}: HTTPS and certificate`, "local http stack"); continue; }
  check(`${label}: served over https`, base.startsWith("https://"));
  try {
    const i = await tlsInfo(base);
    check(`${label}: certificate is valid and trusted`, i.authorized, i.subject);
    check(`${label}: certificate has more than 14 days left`, (i.validTo - Date.now()) / 86400000 > 14, i.validTo.toISOString().slice(0, 10));
    check(`${label}: TLS 1.2 or newer`, ["TLSv1.2", "TLSv1.3"].includes(i.protocol), i.protocol);
  } catch (e) { check(`${label}: TLS handshake`, false, e.message); }
  const http = await get(base.replace(/^https:/, "http:") + "/").catch(() => null);
  check(`${label}: plain http is refused or redirected to https`, !http || (http.status >= 300 && http.status < 400 && hdr(http, "location").startsWith("https://")), http ? `${http.status}` : "no answer");
}

// ---- headers -------------------------------------------------------------------------------------------------------------------
console.log("\n== Security headers");
const home = await get(`${WEB}/fr`);
const csp = hdr(home, "content-security-policy");
check("web: Content-Security-Policy is enforced (not report-only)", !!csp, csp ? "" : "missing");
check("web: CSP forbids framing and plugins", /frame-ancestors 'none'/.test(csp) && /object-src 'none'/.test(csp));
check("web: CSP has no wildcard script/connect source", !/(script-src|connect-src)[^;]*\s\*(\s|;|$)/.test(csp) && !/(script-src|connect-src)[^;]*\shttps:(\s|;|$)/.test(csp));
check("web: no unsafe-eval in the policy", !/unsafe-eval/.test(csp));
check("web: X-Content-Type-Options nosniff", /nosniff/i.test(hdr(home, "x-content-type-options")));
check("web: Referrer-Policy set", !!hdr(home, "referrer-policy"), hdr(home, "referrer-policy"));
check("web: Permissions-Policy set", !!hdr(home, "permissions-policy"));
check("web: X-Frame-Options DENY", /deny/i.test(hdr(home, "x-frame-options")));
const apiHealth = await get(`${API}/actuator/health`);
check("api: health answers 200", apiHealth.status === 200, apiHealth.status);
check("api: X-Content-Type-Options nosniff", /nosniff/i.test(hdr(apiHealth, "x-content-type-options")));
check("api: frame denied", /deny/i.test(hdr(apiHealth, "x-frame-options")) || /frame-ancestors 'none'/.test(hdr(apiHealth, "content-security-policy")));
for (const [label, r] of [["web", home], ["api", apiHealth]]) {
  if (LOCAL) { skip(`${label}: Strict-Transport-Security`, "local http stack"); continue; }
  const m = /max-age=(\d+)/i.exec(hdr(r, "strict-transport-security"));
  check(`${label}: HSTS with max-age >= 6 months`, !!m && Number(m[1]) >= 15552000, hdr(r, "strict-transport-security") || "missing");
}

// ---- CORS ---------------------------------------------------------------------------------------------------------------------
console.log("\n== CORS (exact origins)");
const preflight = (origin) => get(`${API}/api/v1/reservations/lookup`, { method: "OPTIONS", headers: { Origin: origin, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type" } });
const good = await preflight(new URL(WEB).origin);
check("api: the storefront origin is allowed", hdr(good, "access-control-allow-origin") === new URL(WEB).origin, hdr(good, "access-control-allow-origin") || good.status);
for (const bad of ["https://evil.example", "null", `${new URL(WEB).origin}.evil.example`]) {
  const r = await preflight(bad);
  check(`api: origin "${bad}" gets no CORS permission`, !hdr(r, "access-control-allow-origin"), hdr(r, "access-control-allow-origin"));
}
check("api: no wildcard allow-origin", hdr(good, "access-control-allow-origin") !== "*");

// ---- authentication and errors ------------------------------------------------------------------------------------------------
console.log("\n== Authentication and error information");
for (const path of ["/api/v1/admin/payments", "/api/v1/admin/reservations", "/api/v1/admin/products", "/api/v1/me/profile"]) {
  const r = await get(`${API}${path}`);
  check(`api: ${path} without a token is 401`, r.status === 401, r.status);
}
const forged = await get(`${API}/api/v1/admin/payments`, { headers: { Authorization: "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0." } });
check("api: an unsigned (alg=none) token is rejected", forged.status === 401, forged.status);
const leak = /(\bat [a-z]+\.[a-zA-Z.]+\(|Exception|java\.|org\.springframework|com\.anadepot|stacktrace|SQLException|jdbc:)/;
for (const [name, url, init] of [["unknown product", `${API}/api/v1/products/does-not-exist-${Date.now()}`], ["unknown route", `${API}/api/v1/nope`], ["malformed JSON", `${API}/api/v1/reservations/lookup`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{not json" }], ["bad parameter", `${API}/api/v1/products?page=abc&size=-5`]]) {
  const r = await get(url, init);
  const body = await r.text();
  check(`api: ${name} -> ${r.status} without internal details`, r.status >= 400 && r.status < 500 && !leak.test(body), body.slice(0, 80));
}
const pub = await get(`${API}/actuator/prometheus`);
check("api: /actuator/prometheus is not public", pub.status !== 200, pub.status);
for (const p of ["/actuator/env", "/actuator/heapdump", "/actuator/beans"]) { const r = await get(`${API}${p}`); check(`api: ${p} is not exposed`, r.status !== 200, r.status); }

// ---- caching and indexing ---------------------------------------------------------------------------------------------------
console.log("\n== Private pages, caching and indexing");
for (const path of ["/fr/admin", "/fr/account", "/fr/auth/login", "/fr/payment/return?reference=ANA-AAAAAA", "/fr/reservations/ANA-AAAAAA"]) {
  const r = await get(`${WEB}${path}`);
  check(`web: ${path.split("?")[0]} is private and uncached`, /no-store|private/i.test(hdr(r, "cache-control")), `${r.status} ${hdr(r, "cache-control")}`);
}
const robots = await (await get(`${WEB}/robots.txt`)).text();
check("web: robots.txt disallows admin, account and payment pages", /Disallow:\s*\/(?:\*\/)?admin|Disallow: .*admin/i.test(robots) && /payment/i.test(robots) && /account/i.test(robots), robots.split("\n").slice(0, 3).join(" | "));
const sm = await get(`${WEB}/sitemap.xml`);
check("web: sitemap.xml is XML", sm.status === 200 && /xml/i.test(hdr(sm, "content-type")), `${sm.status} ${hdr(sm, "content-type")}`);
const missing = await get(`${WEB}/fr/products/does-not-exist-${Date.now()}`);
check("web: an unknown product is a real 404", missing.status === 404, missing.status);
const man = await get(`${WEB}/manifest.webmanifest`);
check("web: manifest is served", man.status === 200, man.status);
const sw = await get(`${WEB}/sw.js`);
check("web: service worker is served as JavaScript and is not long-cached", sw.status === 200 && /javascript/i.test(hdr(sw, "content-type")) && !/max-age=(?!0)\d+/.test(hdr(sw, "cache-control")) , `${sw.status} ${hdr(sw, "cache-control")}`);

// ---- secrets in the public bundle -----------------------------------------------------------------------------------------------
console.log("\n== Secrets in what every visitor downloads");
const html = await (await get(`${WEB}/fr`)).text();
const scripts = [...new Set([...html.matchAll(/\/_next\/static\/[^"'\s)]+\.js/g)].map((m) => m[0]))];
let scanned = 0;
const hits = [];
const secretRe = /(sk_(live|test)_[A-Za-z0-9]{10,}|rk_(live|test)_[A-Za-z0-9]{10,}|whsec_[A-Za-z0-9]{10,}|sb_secret_[A-Za-z0-9_-]{10,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)/;
for (const s of scripts) {
  const body = await (await get(`${WEB}${s}`)).text().catch(() => "");
  scanned++;
  const m = secretRe.exec(body);
  if (m) hits.push(`${s.split("/").pop()}: ${m[1].slice(0, 12)}…`);
  // A literal word "service_role" is only code (our own guard refuses such a key); a real leak is a JWT whose role claim IS service_role.
  for (const jwt of body.match(/eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}/g) ?? []) {
    try { if (JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString()).role === "service_role") hits.push(`${s.split("/").pop()}: a service_role JWT`); } catch { /* not a JWT */ }
  }
}
check(`web: no secret-shaped value in ${scanned} script file(s)`, scanned > 0 && hits.length === 0, hits.join("; ") || `${scanned} scanned`);

// ---- rate limiting (opt-in) ---------------------------------------------------------------------------------------------------
console.log("\n== Rate limiting");
if (args.includes("--rate-limit")) {
  let got429 = false, retryAfter = "";
  for (let i = 0; i < 120 && !got429; i++) {
    const r = await get(`${API}/api/v1/reservations/lookup`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ reference: "ANA-AAAAAA", phone: "00000000" }) });
    if (r.status === 429) { got429 = true; retryAfter = hdr(r, "retry-after"); }
  }
  check("api: repeated lookups are answered with 429 and Retry-After", got429 && !!retryAfter, `Retry-After ${retryAfter}`);
} else skip("api: 429 after repeated lookups", "run with --rate-limit (changes rate-limit counters)");

console.log(`\n${failed === 0 ? "ALL CHECKS PASSED" : `${failed} CHECK(S) FAILED`}${LOCAL ? "  (local run: TLS/HSTS were skipped and prove nothing about hosting)" : ""}`);
process.exit(failed ? 1 : 0);
