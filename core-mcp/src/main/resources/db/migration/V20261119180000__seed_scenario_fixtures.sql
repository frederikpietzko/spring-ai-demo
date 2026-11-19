-- Hand-written seed: the scenario fixtures of the kaiju claims desk (spec core-claims-mcp). These values never change;
-- later changes and the demo script rely on them. Amounts are minor units of one fictional currency, times are UTC.

INSERT INTO customers (id, "name") VALUES
    (1, 'Mara Okafor'),
    (2, 'Jonas Reef'),
    (3, 'Lena Hartmann'),
    (4, 'Sandbox Customer'),
    (5, 'Theo Marsh');

INSERT INTO policies (id, policy_number, customer_id, valid_from, valid_to, deductible_minor) VALUES
    (1, 'KP-1001', 1, '2026-01-01', '2027-12-31', 50000),
    (2, 'KP-1002', 2, '2026-01-01', '2027-12-31', 100000),
    (3, 'KP-1003', 3, '2026-01-01', '2027-12-31', 25000),
    (4, 'KP-1004', 5, '2026-01-01', '2027-12-31', 20000),
    -- Sandbox policy: the only policy tests write claims for.
    (5, 'KP-9000', 4, '2026-01-01', '2027-12-31', 10000);

INSERT INTO coverages (id, policy_id, category) VALUES
    (1, 1, 'VEHICLE'),
    (2, 1, 'GARDEN'),
    (3, 2, 'BUILDING'),
    (4, 2, 'CONTENTS'),
    (5, 3, 'VEHICLE'),
    (6, 3, 'GARDEN'),
    (7, 4, 'VEHICLE'),
    (8, 5, 'VEHICLE'),
    (9, 5, 'BUILDING'),
    (10, 5, 'CONTENTS'),
    (11, 5, 'GARDEN');

INSERT INTO claims (id, policy_id, status, district, incident_at, kaiju_class, description, filed_at) VALUES
    -- The alibi scenario: a neighbouring-town bulletin later places the kaiju elsewhere at this time.
    (4711, 1, 'FILED', 'HARBOUR', '2026-11-18 15:00:00+00', 3,
     'A kaiju came ashore at the harbour, crushed my parked car and flattened the garden fence.',
     '2026-11-18 17:30:00+00'),
    -- The Act of Kaiju scenario: a class 5 event, which the policy wording excludes.
    (4712, 2, 'FILED', 'OLD_TOWN', '2026-11-16 09:30:00+00', 5,
     'A class 5 kaiju walked through the old town; the house front collapsed.',
     '2026-11-16 12:00:00+00'),
    -- The clean claim.
    (4713, 3, 'FILED', 'RIVERSIDE', '2026-11-17 18:10:00+00', 1,
     'A small kaiju climbed out of the river, dented my car and trampled the vegetable beds.',
     '2026-11-17 20:00:00+00');

INSERT INTO damage_items (id, claim_id, category, description, estimated_amount_minor) VALUES
    (1, 4711, 'VEHICLE', 'Parked car crushed', 800000),
    (2, 4711, 'GARDEN', 'Garden fence flattened', 4500),
    (3, 4712, 'BUILDING', 'House front collapsed', 25000000),
    (4, 4713, 'VEHICLE', 'Car dented', 300000),
    (5, 4713, 'GARDEN', 'Vegetable beds trampled', 80000);

-- Explicit ids do not advance the sequences: continue after the highest id (new claims get numbers above 4713).
SELECT setval(pg_get_serial_sequence('customers', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM customers;
SELECT setval(pg_get_serial_sequence('policies', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM policies;
SELECT setval(pg_get_serial_sequence('coverages', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM coverages;
SELECT setval(pg_get_serial_sequence('claims', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM claims;
SELECT setval(pg_get_serial_sequence('damage_items', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM damage_items;
SELECT setval(pg_get_serial_sequence('payouts', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM payouts;
