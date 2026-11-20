-- Refunds done by the owner in the Stripe dashboard (D1, owner-approved: refunds are NOT made inside the application). The provider tells us through a verified `charge.refunded`
-- event; we only OBSERVE it: the cumulative refunded amount is kept on the attempt so the "refund due" figure on the staff screen clears by itself. The payment ledger is NOT touched
-- (it records money the store received; netting refunds in the books is part of the later in-application refund decision).
alter table payment_attempts add column refunded_amount numeric(19, 2) not null default 0 check (refunded_amount >= 0);
alter table payment_attempts add column refund_observed_at timestamptz;
