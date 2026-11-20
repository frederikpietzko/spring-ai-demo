-- Demo seed set `back-office` (spec demo-seed): the claim the adjuster presentation script reviews, so that script
-- needs no other script before it. Claim 4810 on customer 1's fixture policy KP-1001 (deductible 50000, GARDEN cover):
-- FILED, a big lizard (class 1) ate the rose hedge and flattened the garden fence on HILLTOP at 2026-11-18 16:20,
-- which matches sighting bulletin SB-104, so the fraud check stays CLEAR. Damage items total 120000 (payout 70000
-- once approved).
-- HILLTOP is an analytics district: while this set is applied its claim count is one higher (no promise is made about
-- the HILLTOP numbers). Idempotent: the rows the set owns are deleted first, then inserted again; applying it again
-- after the claim was paid puts it back to FILED without a payout. A claim 4810 filed at runtime is deleted too (the
-- presentation scripts always run `reset` first, so this only matters when the set is applied by hand). `reset`
-- removes the claim (it is numbered above 4713); the policy KP-1001 is a fixture and stays.
-- Damage item ids start at 48101, far above the ids the sequence hands out at runtime, so they never collide.
-- Not a Flyway migration: applied only by the demo tooling. Plain statements only (no psql meta-commands, no
-- procedural blocks), so psql and a JDBC script runner run it identically. Amounts are minor units, times are UTC.

DELETE FROM payouts WHERE claim_id = 4810;

DELETE FROM damage_items WHERE claim_id = 4810 OR id IN (48101, 48102);

DELETE FROM claims WHERE id = 4810;

INSERT INTO claims (id, policy_id, status, district, incident_at, kaiju_class, description, filed_at) VALUES
    (4810, (SELECT id FROM policies WHERE policy_number = 'KP-1001'), 'FILED', 'HILLTOP', '2026-11-18 16:20:00+00', 1,
     'A class 1 kaiju, a big lizard, ate my prize rose hedge and flattened my garden fence.',
     '2026-11-18 18:00:00+00');

INSERT INTO damage_items (id, claim_id, category, description, estimated_amount_minor) VALUES
    (48101, 4810, 'GARDEN', 'Rose hedge eaten', 45000),
    (48102, 4810, 'GARDEN', 'Garden fence flattened', 75000);

-- Explicit ids do not advance the sequences: continue after the highest id (new claims get numbers above 4810).
SELECT setval(pg_get_serial_sequence('claims', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM claims;
SELECT setval(pg_get_serial_sequence('damage_items', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM damage_items;
SELECT setval(pg_get_serial_sequence('payouts', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM payouts;
