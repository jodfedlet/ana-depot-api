#!/usr/bin/env node
/**
 * LOCAL FAKE of the few Stripe endpoints Ana Dépôt uses, for browser E2E on a developer machine. It is NOT Stripe: nothing here talks to Stripe,
 * no card is ever involved, and a green run proves our integration against THIS double only (see docs/payments-stripe.md, "What was verified").
 *
 *   ANA_STRIPE_API_BASE=http://127.0.0.1:9199   (the API's Stripe SDK talks to this server)
 *   node dev/fake-stripe.mjs
 *
 * Env: FAKE_STRIPE_PORT (9199), FAKE_STRIPE_WEBHOOK_URL (http://127.0.0.1:8080/api/v1/webhooks/stripe), FAKE_STRIPE_WEBHOOK_SECRET (the same value as the API's
 * STRIPE_WEBHOOK_SECRET), FAKE_STRIPE_COUNTRY (US), FAKE_STRIPE_CURRENCIES (comma list the "account" can charge; default usd,eur,gbp,cad; HTG is absent on purpose, as in
 * Stripe's published list).
 *
 * Implemented API: GET /v1/account, GET /v1/country_specs/:country, POST /v1/checkout/sessions (idempotent per Idempotency-Key).
 * Fake hosted page: GET /pay/:sessionId with buttons that behave like the customer's choices:
 *   POST /pay/:id/succeed   signed `checkout.session.completed` (paid) + `payment_intent.succeeded`, then redirects to success_url
 *   POST /pay/:id/pending   signed `checkout.session.completed` with payment_status=unpaid (a delayed method), then redirects to success_url
 *   POST /pay/:id/settle    signed `checkout.session.async_payment_succeeded`
 *   POST /pay/:id/fail      signed `checkout.session.async_payment_failed`
 *   POST /pay/:id/expire    signed `checkout.session.expired`
 *   POST /pay/:id/cancel    redirects to cancel_url (the customer pressed "back")
 * Test controls: POST /control/replay/:id (re-sends the last event of that session unchanged: a duplicate delivery), GET /control/sessions, POST /control/reset.
 */
import crypto from "node:crypto";
import http from "node:http";

const PORT = Number(process.env.FAKE_STRIPE_PORT ?? 9199);
const WEBHOOK_URL = process.env.FAKE_STRIPE_WEBHOOK_URL ?? "http://127.0.0.1:8080/api/v1/webhooks/stripe";
const SECRET = process.env.FAKE_STRIPE_WEBHOOK_SECRET ?? "whsec_fake_local_only";
const COUNTRY = process.env.FAKE_STRIPE_COUNTRY ?? "US";
const CURRENCIES = (process.env.FAKE_STRIPE_CURRENCIES ?? "usd,eur,gbp,cad").split(",").map((c) => c.trim());

/** @type {Map<string, any>} */
const sessions = new Map();
/** @type {Map<string, string>} */
const byIdempotencyKey = new Map();
/** @type {Map<string, string>} */
const lastEvent = new Map();

const json = (res, status, body) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
const readBody = (req) => new Promise((resolve) => { const chunks = []; req.on("data", (c) => chunks.push(c)); req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8"))); });
const sign = (payload) => { const t = Math.floor(Date.now() / 1000); return `t=${t},v1=${crypto.createHmac("sha256", SECRET).update(`${t}.${payload}`).digest("hex")}`; };

async function deliver(type, session, extra = {}) {
  const intent = session.payment_intent ?? `pi_fake_${session.id.slice(8)}`;
  session.payment_intent = intent;
  const object = type.startsWith("payment_intent.")
    ? { id: intent, object: "payment_intent", amount: session.amount_total, amount_received: type === "payment_intent.succeeded" ? session.amount_total : 0, currency: session.currency, metadata: session.metadata, ...extra }
    : { ...session, ...extra };
  const payload = JSON.stringify({ id: `evt_fake_${crypto.randomUUID().replaceAll("-", "")}`, object: "event", type, created: Math.floor(Date.now() / 1000), data: { object } });
  lastEvent.set(session.id, payload);
  const r = await fetch(WEBHOOK_URL, { method: "POST", headers: { "Content-Type": "application/json", "Stripe-Signature": sign(payload) }, body: payload });
  return r.status;
}

const page = (s) => `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FAKE Stripe Checkout</title>
<body style="font-family:system-ui;max-width:28rem;margin:2rem auto;padding:0 1rem">
<p style="background:#fff3cd;padding:.75rem;border-radius:.5rem"><b>FAKE Stripe test double</b> — local verification only. No card, no real payment.</p>
<h1 style="font-size:1.25rem">${s.line_items_name}</h1><p id="amount" style="font-size:1.5rem"><b>${(s.amount_total / 100).toFixed(2)} ${s.currency.toUpperCase()}</b></p>
${["succeed:Pay (success)", "pending:Pay (delayed method, still processing)", "fail:Pay (payment fails)", "expire:Let the session expire"].map((x) => { const [a, l] = x.split(":"); return `<form method="post" action="/pay/${s.id}/${a}"><button style="width:100%;padding:.8rem;margin:.3rem 0">${l}</button></form>`; }).join("")}
<form method="post" action="/pay/${s.id}/cancel"><button style="width:100%;padding:.8rem;margin:.3rem 0">← Back to Ana Dépôt</button></form></body>`;

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const path = url.pathname;
  try {
    if (req.method === "GET" && path === "/v1/account") return json(res, 200, { id: "acct_fake", object: "account", country: COUNTRY });
    if (req.method === "GET" && path.startsWith("/v1/country_specs/")) return json(res, 200, { id: path.split("/").pop(), object: "country_spec", supported_payment_currencies: CURRENCIES });
    if (req.method === "POST" && path === "/v1/checkout/sessions") {
      const form = new URLSearchParams(await readBody(req));
      const idem = req.headers["idempotency-key"];
      if (idem && byIdempotencyKey.has(idem)) return json(res, 200, sessions.get(byIdempotencyKey.get(idem)).api);
      if (form.get("adaptive_pricing[enabled]") !== "false") return json(res, 400, { error: { type: "invalid_request_error", code: "adaptive_pricing_not_disabled", message: "Ana Dépôt requires adaptive_pricing[enabled]=false (no automatic currency conversion)" } });
      const currency = form.get("line_items[0][price_data][currency]");
      const amount = Number(form.get("line_items[0][price_data][unit_amount]"));
      if (!CURRENCIES.includes(currency)) return json(res, 400, { error: { type: "invalid_request_error", code: "currency_not_supported", message: "Invalid currency" } });
      if (currency === "usd" && amount < 50) return json(res, 400, { error: { type: "invalid_request_error", code: "amount_too_small", message: "Amount too small" } });
      const id = `cs_test_${crypto.randomUUID().replaceAll("-", "")}`;
      const metadata = {};
      for (const [k, v] of form) { const m = k.match(/^metadata\[(.+)\]$/); if (m) metadata[m[1]] = v; }
      const api = { id, object: "checkout.session", url: `http://127.0.0.1:${PORT}/pay/${id}`, mode: "payment", ui_mode: "hosted_page", amount_total: amount, currency, payment_status: "unpaid", status: "open",
        expires_at: Number(form.get("expires_at")), client_reference_id: form.get("client_reference_id"), customer_email: form.get("customer_email"), metadata,
        success_url: form.get("success_url"), cancel_url: form.get("cancel_url") };
      sessions.set(id, { ...api, api, line_items_name: form.get("line_items[0][price_data][product_data][name]") ?? "Order" });
      if (idem) byIdempotencyKey.set(idem, id);
      return json(res, 200, api);
    }
    const pay = path.match(/^\/pay\/(cs_test_[a-f0-9]+)(?:\/(\w+))?$/);
    if (pay) {
      const s = sessions.get(pay[1]);
      if (!s) { res.writeHead(404); return res.end("unknown session"); }
      if (req.method === "GET" && !pay[2]) { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); return res.end(page(s)); }
      if (req.method === "POST") {
        const action = pay[2];
        let status = 200;
        if (action === "succeed") { s.payment_status = "paid"; status = await deliver("checkout.session.completed", s, { payment_status: "paid", status: "complete" }); await deliver("payment_intent.succeeded", s); }
        else if (action === "pending") status = await deliver("checkout.session.completed", s, { payment_status: "unpaid", status: "complete" });
        else if (action === "settle") status = await deliver("checkout.session.async_payment_succeeded", s, { payment_status: "paid", status: "complete" });
        else if (action === "fail") status = await deliver("payment_intent.payment_failed", s, { last_payment_error: { code: "card_declined" } });
        else if (action === "expire") status = await deliver("checkout.session.expired", s, { status: "expired" });
        const target = action === "cancel" || action === "expire" ? s.cancel_url : action === "fail" ? s.cancel_url : s.success_url;
        console.log(`fake-stripe ${action} ${s.id} -> webhook ${status}`);
        res.writeHead(303, { Location: target }); return res.end();
      }
    }
    const replay = path.match(/^\/control\/replay\/(cs_test_[a-f0-9]+)$/);
    if (req.method === "POST" && replay) {
      const payload = lastEvent.get(replay[1]);
      if (!payload) return json(res, 404, { error: "no event yet" });
      const r = await fetch(WEBHOOK_URL, { method: "POST", headers: { "Content-Type": "application/json", "Stripe-Signature": sign(payload) }, body: payload });
      return json(res, 200, { webhookStatus: r.status });
    }
    if (req.method === "GET" && path === "/control/sessions") return json(res, 200, [...sessions.values()].map((s) => s.api));
    if (req.method === "POST" && path === "/control/reset") { sessions.clear(); byIdempotencyKey.clear(); lastEvent.clear(); return json(res, 200, { ok: true }); }
    json(res, 404, { error: { message: `fake-stripe: ${req.method} ${path} is not implemented` } });
  } catch (e) { console.error("fake-stripe error", e); json(res, 500, { error: { message: String(e) } }); }
}).listen(PORT, "127.0.0.1", () => console.log(`FAKE Stripe on http://127.0.0.1:${PORT} (webhook -> ${WEBHOOK_URL}, country ${COUNTRY}, currencies ${CURRENCIES.join(",")})`));
