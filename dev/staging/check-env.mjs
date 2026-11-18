#!/usr/bin/env node
/**
 * Validates a pair of FILLED staging env files (API + web) against the rules that bite in practice. Offline: it only reads the two files, prints no secret
 * values, and talks to nothing.
 *
 *   node dev/staging/check-env.mjs <api.env> <web.env>
 *
 * Exit 0 = no errors (warnings may remain); 1 = at least one error. Placeholders such as <secret> count as "not filled in".
 */
import { readFileSync } from "node:fs";

const [apiFile, webFile] = process.argv.slice(2);
if (!apiFile || !webFile) {
  console.error("usage: node dev/staging/check-env.mjs <api.env> <web.env>");
  process.exit(2);
}

const parse = (file) => {
  const out = {};
  for (const raw of readFileSync(file, "utf8").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const i = line.indexOf("=");
    if (i < 0) continue;
    out[line.slice(0, i).trim()] = line.slice(i + 1).trim().replace(/^["']|["']$/g, "");
  }
  return out;
};
const api = parse(apiFile);
const web = parse(webFile);

const errors = [];
const warnings = [];
const err = (m) => errors.push(m);
const warn = (m) => warnings.push(m);
const placeholder = (v) => !v || /<[^>]*>/.test(v);
const filled = (env, k) => !placeholder(env[k]);
const need = (env, k, label) => { if (!filled(env, k)) err(`${label} ${k} is missing or still a placeholder`); };
const origin = (v) => { try { const u = new URL(v); return u.origin; } catch { return null; } };
const isHttps = (v) => { try { return new URL(v).protocol === "https:"; } catch { return false; } };
const flag = (v) => /^(1|true|yes)$/i.test(v ?? "");

// ---- required values -----------------------------------------------------------------------------------------------------
for (const k of ["SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SUPABASE_URL", "R2_ACCOUNT_ID", "R2_BUCKET_NAME", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_PUBLIC_BASE_URL", "ANA_CORS_ALLOWED_ORIGINS", "ANA_TERMS_VERSION"]) need(api, k, "API");
for (const k of ["NEXT_PUBLIC_SITE_URL", "NEXT_PUBLIC_API_BASE_URL", "NEXT_PUBLIC_SUPABASE_URL", "NEXT_PUBLIC_TERMS_VERSION", "NEXT_PUBLIC_ASSET_ORIGINS"]) need(web, k, "web");
if (!filled(web, "NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY") && !filled(web, "NEXT_PUBLIC_SUPABASE_ANON_KEY")) err("web needs NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY (or _ANON_KEY)");
if (api.SPRING_PROFILES_ACTIVE !== "prod") err("API SPRING_PROFILES_ACTIVE must be prod (the production start-up guard refuses an incomplete configuration)");

// ---- database ------------------------------------------------------------------------------------------------------------
const dbUrl = api.SPRING_DATASOURCE_URL ?? "";
if (filled(api, "SPRING_DATASOURCE_URL")) {
  if (!dbUrl.startsWith("jdbc:postgresql:")) err("SPRING_DATASOURCE_URL must be a jdbc:postgresql: URL");
  if (!/sslmode=(require|verify-ca|verify-full)/.test(dbUrl)) err("SPRING_DATASOURCE_URL needs sslmode=require (or stronger): Supabase accepts only TLS from outside its network");
  if (dbUrl.includes(":6543")) err("port 6543 is the transaction pooler: Flyway's session advisory lock does not work there (use the direct connection or the session pooler on :5432)");
  if (/localhost|127\.0\.0\.1/.test(dbUrl)) err("SPRING_DATASOURCE_URL points at localhost: staging must use the hosted staging project");
}

// ---- terms version (the one value that must be identical in two repositories) -------------------------------------------------
if (filled(api, "ANA_TERMS_VERSION") && filled(web, "NEXT_PUBLIC_TERMS_VERSION") && api.ANA_TERMS_VERSION !== web.NEXT_PUBLIC_TERMS_VERSION) {
  err("terms version mismatch: the API's ANA_TERMS_VERSION and the web's NEXT_PUBLIC_TERMS_VERSION must be IDENTICAL (otherwise every reservation is refused as 'terms_not_acknowledged')");
}

// ---- URLs and origins ----------------------------------------------------------------------------------------------------
for (const [env, k] of [[web, "NEXT_PUBLIC_SITE_URL"], [web, "NEXT_PUBLIC_API_BASE_URL"], [web, "NEXT_PUBLIC_SUPABASE_URL"], [api, "SUPABASE_URL"], [api, "R2_PUBLIC_BASE_URL"], [api, "ANA_STRIPE_RETURN_BASE_URL"]]) {
  if (filled(env, k) && !isHttps(env[k])) err(`${k} must be an https:// URL in staging`);
}
const site = filled(web, "NEXT_PUBLIC_SITE_URL") ? origin(web.NEXT_PUBLIC_SITE_URL) : null;
const cors = (api.ANA_CORS_ALLOWED_ORIGINS ?? "").split(",").map((s) => s.trim()).filter(Boolean);
if (site && filled(api, "ANA_CORS_ALLOWED_ORIGINS") && !cors.includes(site)) err(`ANA_CORS_ALLOWED_ORIGINS must contain the storefront origin ${site} exactly`);
for (const c of cors) { if (c === "*" || c.includes("*")) err("ANA_CORS_ALLOWED_ORIGINS must not contain a wildcard"); if (c.endsWith("/")) err(`CORS origin "${c}" must not end with a slash`); }
if (filled(api, "ANA_STRIPE_RETURN_BASE_URL") && site && origin(api.ANA_STRIPE_RETURN_BASE_URL) !== site) err("ANA_STRIPE_RETURN_BASE_URL must be the storefront origin (customers return there from Stripe)");
if (filled(web, "NEXT_PUBLIC_SUPABASE_URL") && filled(api, "SUPABASE_URL") && origin(web.NEXT_PUBLIC_SUPABASE_URL) !== origin(api.SUPABASE_URL)) err("web NEXT_PUBLIC_SUPABASE_URL and API SUPABASE_URL must be the SAME project");
if (filled(api, "SUPABASE_JWT_ISSUER") && filled(api, "SUPABASE_URL") && api.SUPABASE_JWT_ISSUER.replace(/\/+$/, "") !== `${api.SUPABASE_URL.replace(/\/+$/, "")}/auth/v1`) err("SUPABASE_JWT_ISSUER must be <SUPABASE_URL>/auth/v1");
if (filled(web, "NEXT_PUBLIC_ASSET_ORIGINS") && filled(api, "R2_PUBLIC_BASE_URL")) {
  const assets = web.NEXT_PUBLIC_ASSET_ORIGINS.split(",").map((s) => origin(s.trim()));
  if (!assets.includes(origin(api.R2_PUBLIC_BASE_URL))) err("NEXT_PUBLIC_ASSET_ORIGINS must include the origin of the API's R2_PUBLIC_BASE_URL (otherwise the CSP blocks every product image)");
}

// ---- secrets must stay out of the browser bundle ---------------------------------------------------------------------------
for (const [k, v] of Object.entries(web)) {
  if (!k.startsWith("NEXT_PUBLIC_")) continue;
  if (/service[_-]?role|secret|password|private|sk_(live|test)_|whsec_/i.test(k) || /sk_(live|test)_|whsec_|service_role|eyJ[A-Za-z0-9_-]{20,}\./.test(v)) err(`${k} looks like a secret in a NEXT_PUBLIC_ variable: it would ship to every visitor`);
}
if (web.NEXT_PUBLIC_CHECKOUT_TEST_ORIGIN) err("NEXT_PUBLIC_CHECKOUT_TEST_ORIGIN is for a developer machine only: remove it from staging");
if (api.ANA_STRIPE_API_BASE) err("ANA_STRIPE_API_BASE points the SDK at a test double: remove it from staging");

// ---- Stripe (TEST keys only in staging) ------------------------------------------------------------------------------------
if (flag(api.ANA_STRIPE_ENABLED)) {
  if (!/^sk_test_/.test(api.STRIPE_SECRET_KEY ?? "")) err("STRIPE_SECRET_KEY must be a TEST key (sk_test_...) in staging; a live key or a placeholder is refused");
  if (!/^whsec_/.test(api.STRIPE_WEBHOOK_SECRET ?? "")) err("STRIPE_WEBHOOK_SECRET must be the signing secret (whsec_...) of the staging webhook endpoint");
  if (!/^[A-Z]{3}(,[A-Z]{3})*$/.test(api.ANA_STRIPE_ENABLED_CURRENCIES ?? "")) err("ANA_STRIPE_ENABLED_CURRENCIES must be a comma list of currency codes (USD, HTG); empty offers nothing");
  const minutes = Number(api.ANA_STRIPE_CHECKOUT_EXPIRATION_MINUTES ?? 30);
  if (!(minutes >= 30 && minutes <= 1440)) err("ANA_STRIPE_CHECKOUT_EXPIRATION_MINUTES must be between 30 and 1440");
} else {
  warn("ANA_STRIPE_ENABLED is not true: online payment will not be verified on staging");
}

// ---- HTTPS, headers, proxies, monitoring -----------------------------------------------------------------------------------
if (!flag(api.ANA_HSTS_ENABLED)) err("ANA_HSTS_ENABLED must be true on an HTTPS-only staging deployment");
if (!flag(web.SECURITY_HTTPS_ONLY)) err("web SECURITY_HTTPS_ONLY must be true on an HTTPS-only staging deployment (HSTS, Secure auth cookies)");
if (placeholder(api.ANA_RATE_LIMIT_TRUSTED_PROXIES)) warn("ANA_RATE_LIMIT_TRUSTED_PROXIES is empty: behind a proxy every client shares one rate-limit bucket (and X-Forwarded-For is ignored)");
if (placeholder(api.MANAGEMENT_SERVER_PORT)) warn("MANAGEMENT_SERVER_PORT is not set: there is no private metrics endpoint for a scraper (monitoring not wired)");
if (placeholder(api.ANA_BOOTSTRAP_ADMIN_SUBJECT)) warn("ANA_BOOTSTRAP_ADMIN_SUBJECT is empty: nobody will be staff until a staff row is created");
if (filled(web, "NEXT_PUBLIC_SITE_URL") === false) warn("NEXT_PUBLIC_SITE_URL is empty: canonical URLs, hreflang, Open Graph and the sitemap are omitted");

console.log(`API:  ${apiFile}\nWeb:  ${webFile}\n`);
for (const w of warnings) console.log(`WARN   ${w}`);
for (const e of errors) console.log(`ERROR  ${e}`);
console.log(`\n${errors.length} error(s), ${warnings.length} warning(s).`);
process.exit(errors.length ? 1 : 0);
