# Decisions the owner must make

Nothing below is implemented as a business rule unless it says so. Where the software has to do *something* today, that **current behaviour** is stated so nobody is surprised. Each decision has: why it matters, what the software does now, a **recommended default**, what changing it costs technically, and the question to answer. Reply with the decision number and your choice (for example "D3: option A").

Written for: the shop owner (no technical knowledge assumed). Technical readers: `docs/domain.md`, `docs/payments-stripe.md`, `docs/auth.md`.

---

## A. Money

### D1. Refunds
- **Why it matters:** if a customer paid online and the store cannot fill the order, they expect their money back. Today there is no way to give it back inside the system.
- **What happens now:** a staff member cannot undo an online (Stripe) payment in the application: the system refuses, because undoing it in our books would **not** return the money to the customer's card. A cash payment can be reversed in the books (staff only, while the reservation is ready for pickup).
- **Recommended default:** refunds are done by the owner in the Stripe dashboard (no code), and a staff member records a note on the reservation. When you want it inside the application, we add a "Refund" action that calls Stripe, is limited to the owner, needs a reason, and writes a refund line in the ledger only after Stripe confirms.
- **Technical consequence of "inside the application":** a new refund flow (Stripe refund API, a webhook for refund events, a new ledger entry type, reports, customer wording). About a week of work plus tests.
- **Question:** who may refund, how long after payment, full or partial, and who pays any fee Stripe keeps?

### D2. Partial payments
- **Why:** customers sometimes want to pay half now and half at pickup.
- **Now:** cash: the store may record any amount up to and beyond the balance (see D3). Online: the customer can only pay the **full remaining amount of one currency**; there is no "pay half" online.
- **Recommended default:** cash partial payments allowed (already works); online stays full-amount only. A reservation is "paid" only when every currency is fully settled.
- **Consequence of allowing partial online payments:** the customer chooses an amount, the backend validates it against the balance, and the same "one open attempt per currency" rule applies. Small change, but the customer screens need an amount field.
- **Question:** do you want partial online payments? Is there a minimum?

### D3. Overpayments
- **Why:** a customer hands over more cash than owed.
- **Now:** the store may record it; the amount due never goes below zero and the original amount is kept. Nothing says what happens to the extra. Online, overpayment cannot occur (the amount is fixed by the system).
- **Recommended default:** refuse a cash payment larger than the remaining balance and tell the staff member to give change. This removes the ambiguity entirely.
- **Consequence:** a one-line rule in the payment use case plus a clear message in both languages; existing overpayment tests are updated by approval.
- **Question:** refuse, or accept and treat the extra as a credit? (Credit needs an accounting policy.)

### D4. Online payment before the order is ready
- **Why:** paying earlier reduces no-shows; paying too early risks paying for something the store later cannot supply.
- **Now:** online payment opens only when the reservation is **ready for pickup**.
- **Recommended default:** keep it (the customer pays only for stock the store has confirmed and set aside).
- **Consequence of opening earlier (for example when "confirmed"):** a rejected or cancelled reservation could already be paid, which makes refunds (D1) mandatory first.
- **Question:** keep "after ready", or allow payment once the store has confirmed?

### D5. Currency and fees on online payments (HTG, USD)
- **Why:** it depends on the **Stripe account** you create (merchant country, which currencies it can charge and settle in, fees). This cannot be decided in software.
- **Now:** online payment is **off** until the Ana Dépôt merchant account exists. The code offers a currency only if that account can charge it; it never converts HTG to USD.
- **Question:** in which country will the Ana Dépôt merchant account be created, and do you accept the fees and the settlement currency Stripe shows for it?

## B. Reservations

### D6. Reservation hold period (automatic expiry)
- **Why:** reserved stock is unavailable to others; without an expiry a customer who never comes keeps the stock blocked.
- **Now:** **nothing expires automatically.** The store can set a number of days in the pickup settings, but no job uses it. Staff cancel manually.
- **Recommended default:** a ready reservation expires 3 days after it became ready, with a reminder, then stock is released automatically and the customer is told. Starting at 3 days is typical; you choose the number.
- **Consequence:** a scheduled job, a notification (D10), clear wording to customers, and tests. Payments already received stay on the ledger (see D1).
- **Question:** hold period in days, and what happens to money already paid when a reservation expires?

### D7. Customer cancellation
- **Why:** customers change their mind.
- **Now:** customers cannot cancel; only the store can. The customer page does not show a cancel button.
- **Recommended default:** a customer may cancel while the reservation is *pending or confirmed* (not after it is ready), giving a reason; it frees the stock at once. After ready they contact the store.
- **Consequence:** one customer endpoint (ownership proven by reference + phone or account), a button, history entry "cancelled by customer", and tests.
- **Question:** until which status may a customer cancel?

### D8. Changing the pickup place for one reservation
- **Now:** a pickup change in settings applies to **new** reservations only; each reservation keeps the address it was made with. There is no way to change it for one reservation.
- **Recommended default:** staff can edit one reservation's pickup note with a reason (audited) and the customer sees it.
- **Question:** is this needed at launch?

## C. Catalog and prices

### D9. Wholesale prices: who sees them?
- **Now:** quantity prices are visible to **everyone**.
- **Recommended default:** keep public (simplest, honest). Alternative: only approved wholesale customers see them, which needs an approval workflow and account types.
- **Question:** public, or approved customers only?

### D10. Selling units and fractional quantities
- **Now:** the unit list is unit, pair, pack, box, set, meter, kilogram, but quantities are **whole numbers**, and there is **no conversion** (one box is not "12 units" anywhere). Selling 2.5 meters or 0.5 kg is not possible.
- **Recommended default:** whole numbers only at launch; if you sell by meter or kilo, decide the smallest step (for example 0.5).
- **Consequence of decimals:** stock, prices and reservation quantities become decimals (migration and many tests). Conversions (box to units) need a per-product factor you provide; I will not invent one.
- **Question:** do you sell by length or weight? What is the smallest step?

### D11. Low stock
- **Now:** a product counts as "low" only if you set a threshold on it. No default threshold exists.
- **Recommended default:** show the threshold field on each product and keep the dashboard alert; do not impose a global number.

### D12. Minimum order for the whole shop
- **Now:** minimum order is per product and currency. There is no shop-wide minimum.
- **Question:** do you want a shop-wide minimum (for example a total amount)? It would have to be per currency.

### D13. Featured and new products on the home page
- **Now:** the home page shows the newest products. There is no "featured" flag for hand-picked items.
- **Recommended default:** add a simple "featured" switch per product if you want to choose what is shown first.

## D. People and security

### D14. Staff roles
- **Now:** everyone on the team has **all** rights: record payments, change prices, invite and remove colleagues. The team page says so.
- **Recommended default:** two roles: **Owner** (everything, including team and settings) and **Staff** (reservations, payments, inventory, no prices, no team). Only an Owner can invite or remove people.
- **Consequence:** a role on each team member, checks in every protected endpoint, tests per role, and a screen change. About a week.
- **Question:** which people need which rights?

### D15. Mandatory second step for staff sign-in (MFA)
- **Now:** implemented and **verified on the local test setup**. A setting makes it mandatory (`ANA_STAFF_REQUIRE_MFA`, off by default so nobody is locked out by surprise). With it on, a team member must enter a 6-digit code from an authenticator app after the password.
- **Recommended default:** **turn it on for production**, and make sure at least two people have enrolled before enforcing.
- **Hosted note:** the hosted Supabase project must have TOTP multi-factor enabled in its settings; this is only verifiable once that project exists.
- **Question:** approve "required for all team members in production"?

### D16. Sign-in cookie visible to the page's scripts
- **Now:** the browser keeps the sign-in session in a cookie that the page's own scripts can read (this is how the Supabase library works when the page calls the API directly). It is protected by a strict content policy, HTTPS and short-lived tokens, but a script injected into a page could read it.
- **Recommended default:** accept for launch, with the strict policy and MFA above; revisit when you have traffic and a security review.
- **Alternative:** route all API calls through the web server so the cookie can be hidden from scripts (a large change: every call, and a new server layer).
- **Question:** accept the risk for launch, or require the larger change before launch?

### D17. Content policy still allows inline scripts
- **Now:** the policy allows inline scripts because Next.js needs them to start a page; removing that requires the server to build every page for every visitor (slower, more costly), which fights the speed goal.
- **Recommended default:** accept for launch (images, frames, forms and external scripts are already locked down).

## E. Words and law

### D18. Legal pages and terms
- **Now:** privacy, returns and terms pages are placeholders; the terms version label must be set and match in both applications before reservations work.
- **I cannot write legal text.** Needed: the text (French, Haitian Creole), reviewed by you or a lawyer, and a version label such as `ana-depot-terms-2026-11-v1`.

### D19. How customers are told about their reservation (notifications)
- **Now:** no messages are sent. The customer sees the status and the store's note when they open the tracking page.
- **Recommended default:** e-mail first (the account or guest e-mail, when given), WhatsApp later if you use it. Messages: received, ready, reminder, rejected, cancelled.
- **Consequence:** an e-mail provider (cost, domain verification), templates in both languages, and a send log. Guests without an e-mail get nothing, so the phone number remains the key.
- **Question:** which channel, and from which address?

### D20. Store contact details and opening hours
- **Now:** the pickup settings hold address, phone and opening hours. There is **no** separate store-identity page (name for receipts, email, WhatsApp, social links); the contact page says details will be communicated.
- **Question:** which contact details should customers see, and should they all come from one settings page?

### D21. Haitian Creole review
- All Creole text is written carefully but **no native speaker has reviewed it**. `ana-depot-web/docs/creole-review.md` lists the points to check. Needed before launch: a native reviewer.
