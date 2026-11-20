-- Demo seed set `desk-queue` (spec demo-seed): a small queue of claims in several states for the adjuster, on three
-- policies of their own: 4801 is FILED, 4802 UNDER_REVIEW and 4803 APPROVED (ready for its payout). Only the
-- districts NEON_MARKET and RIVERSIDE, so the analytics districts (HARBOUR, OLD_TOWN, HILLTOP) keep their numbers.
-- Idempotent: the rows the set owns are deleted first, then inserted again. `reset` removes them.
-- Damage item ids start at 48011, far above the ids the sequence hands out at runtime, so they never collide.
-- Not a Flyway migration: applied only by the demo tooling. Plain statements only (no psql meta-commands, no
-- procedural blocks), so psql and a JDBC script runner run it identically. Amounts are minor units, times are UTC.

DELETE FROM payouts
WHERE claim_id IN (4801, 4802, 4803)
   OR claim_id IN (
       SELECT c.id FROM claims c JOIN policies p ON p.id = c.policy_id
       WHERE p.policy_number IN ('KP-3001', 'KP-3002', 'KP-3003')
   );

DELETE FROM damage_items
WHERE claim_id IN (4801, 4802, 4803)
   OR claim_id IN (
       SELECT c.id FROM claims c JOIN policies p ON p.id = c.policy_id
       WHERE p.policy_number IN ('KP-3001', 'KP-3002', 'KP-3003')
   );

DELETE FROM claims
WHERE id IN (4801, 4802, 4803)
   OR policy_id IN (SELECT id FROM policies WHERE policy_number IN ('KP-3001', 'KP-3002', 'KP-3003'));

DELETE FROM coverages
WHERE policy_id IN (SELECT id FROM policies WHERE policy_number IN ('KP-3001', 'KP-3002', 'KP-3003'));

DELETE FROM policies WHERE policy_number IN ('KP-3001', 'KP-3002', 'KP-3003');

DELETE FROM customers WHERE id IN (201, 202, 203);

INSERT INTO customers (id, "name") VALUES
    (201, 'Kenji Moreau'),
    (202, 'Priya Solberg'),
    (203, 'Oskar Lund');

INSERT INTO policies (id, policy_number, customer_id, valid_from, valid_to, deductible_minor) VALUES
    (201, 'KP-3001', 201, '2026-01-01', '2027-12-31', 25000),
    (202, 'KP-3002', 202, '2026-01-01', '2027-12-31', 50000),
    (203, 'KP-3003', 203, '2026-01-01', '2027-12-31', 20000);

INSERT INTO coverages (id, policy_id, category) VALUES
    (201, 201, 'VEHICLE'),
    (202, 201, 'BUILDING'),
    (203, 202, 'BUILDING'),
    (204, 202, 'CONTENTS'),
    (205, 202, 'GARDEN'),
    (206, 203, 'VEHICLE'),
    (207, 203, 'CONTENTS'),
    (208, 203, 'GARDEN');

INSERT INTO claims (id, policy_id, status, district, incident_at, kaiju_class, description, filed_at) VALUES
    (4801, 201, 'FILED', 'NEON_MARKET', '2026-11-19 21:30:00+00', 2,
     'A kaiju tripped over the neon market stalls and sat on my scooter.',
     '2026-11-19 23:00:00+00'),
    (4802, 202, 'UNDER_REVIEW', 'RIVERSIDE', '2026-11-18 06:45:00+00', 3,
     'A kaiju waded up the river and pushed my boathouse off its posts.',
     '2026-11-18 09:00:00+00'),
    (4803, 203, 'APPROVED', 'NEON_MARKET', '2026-11-17 19:20:00+00', 1,
     'A small kaiju chewed the shop hedge and knocked over the shelves inside.',
     '2026-11-17 21:00:00+00');

INSERT INTO damage_items (id, claim_id, category, description, estimated_amount_minor) VALUES
    (48011, 4801, 'VEHICLE', 'Scooter flattened', 180000),
    (48021, 4802, 'BUILDING', 'Boathouse pushed off its posts', 640000),
    (48022, 4802, 'CONTENTS', 'Rowing boat and gear lost', 90000),
    (48031, 4803, 'GARDEN', 'Shop hedge chewed down', 35000),
    (48032, 4803, 'CONTENTS', 'Shop shelves knocked over', 120000);

-- Explicit ids do not advance the sequences: continue after the highest id (new claims get numbers above 4803).
SELECT setval(pg_get_serial_sequence('customers', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM customers;
SELECT setval(pg_get_serial_sequence('policies', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM policies;
SELECT setval(pg_get_serial_sequence('coverages', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM coverages;
SELECT setval(pg_get_serial_sequence('claims', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM claims;
SELECT setval(pg_get_serial_sequence('damage_items', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM damage_items;
SELECT setval(pg_get_serial_sequence('payouts', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM payouts;
