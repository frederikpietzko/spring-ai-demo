-- Hand-written seed: the claims history for the analytics tools (spec core-claims-mcp, change claims-analytics).
-- Separate from the scenario fixtures: own customers (101-103), policies (KP-2001 to KP-2003) and claim numbers
-- (4601-4612, below the fixtures). Dates are fixed in October/November 2026 so the aggregates are deterministic.
-- Tests never write these rows and never file claims in HARBOUR, OLD_TOWN or HILLTOP (ADR-0004, module core-mcp).
-- Amounts are minor units of one fictional currency, times are UTC; a payout is the damage minus the deductible.

INSERT INTO customers (id, "name") VALUES
    (101, 'Ada Lindqvist'),
    (102, 'Bram Osei'),
    (103, 'Cora Vance');

INSERT INTO policies (id, policy_number, customer_id, valid_from, valid_to, deductible_minor) VALUES
    (101, 'KP-2001', 101, '2026-01-01', '2027-12-31', 20000),
    (102, 'KP-2002', 102, '2026-01-01', '2027-12-31', 50000),
    (103, 'KP-2003', 103, '2026-01-01', '2027-12-31', 10000);

INSERT INTO coverages (id, policy_id, category) VALUES
    (101, 101, 'VEHICLE'),
    (102, 101, 'BUILDING'),
    (103, 101, 'CONTENTS'),
    (104, 101, 'GARDEN'),
    (105, 102, 'VEHICLE'),
    (106, 102, 'BUILDING'),
    (107, 102, 'CONTENTS'),
    (108, 102, 'GARDEN'),
    (109, 103, 'VEHICLE'),
    (110, 103, 'BUILDING'),
    (111, 103, 'CONTENTS'),
    (112, 103, 'GARDEN');

INSERT INTO claims (id, policy_id, status, district, incident_at, kaiju_class, description, filed_at) VALUES
    (4601, 101, 'PAID', 'HARBOUR', '2026-11-02 10:00:00+00', 4,
     'A kaiju surfaced next to the quay and knocked over the warehouse wall.', '2026-11-02 12:00:00+00'),
    (4602, 101, 'PAID', 'HARBOUR', '2026-11-05 14:00:00+00', 4,
     'The tail of a kaiju swept the delivery van into the dock.', '2026-11-05 16:00:00+00'),
    (4603, 102, 'REJECTED', 'HARBOUR', '2026-11-09 09:00:00+00', 4,
     'A kaiju leaned on the boathouse; the roof caved in.', '2026-11-09 11:00:00+00'),
    (4604, 102, 'FILED', 'HARBOUR', '2026-11-12 16:00:00+00', 4,
     'A kaiju wave flooded the ground floor of the harbour office.', '2026-11-12 18:00:00+00'),
    (4605, 103, 'PAID', 'HARBOUR', '2026-11-14 11:00:00+00', 2,
     'A small kaiju chewed through the fishing shed.', '2026-11-14 13:00:00+00'),
    (4606, 103, 'PAID', 'OLD_TOWN', '2026-11-03 08:00:00+00', 3,
     'A kaiju squeezed through the old town lanes and cracked the shop front.', '2026-11-03 10:00:00+00'),
    (4607, 101, 'PAID', 'OLD_TOWN', '2026-11-08 13:00:00+00', 3,
     'A kaiju sat down on the parked car in the market square.', '2026-11-08 15:00:00+00'),
    (4608, 102, 'UNDER_REVIEW', 'OLD_TOWN', '2026-11-13 10:00:00+00', 5,
     'A class 5 kaiju crossed the old town; the townhouse lost its upper floors.', '2026-11-13 12:00:00+00'),
    (4609, 103, 'PAID', 'HILLTOP', '2026-10-20 12:00:00+00', 2,
     'A kaiju trampled the hilltop vineyard terraces.', '2026-10-20 14:00:00+00'),
    (4610, 101, 'PAID', 'HILLTOP', '2026-10-28 09:30:00+00', 1,
     'A tiny kaiju dug up the flower beds.', '2026-10-28 11:30:00+00'),
    (4611, 102, 'REJECTED', 'HILLTOP', '2026-11-06 15:00:00+00', 1,
     'A kaiju sniffed at the garden furniture and tipped it over.', '2026-11-06 17:00:00+00'),
    (4612, 103, 'FILED', 'HILLTOP', '2026-11-15 10:00:00+00', 2,
     'A kaiju rolled down the hill into the conservatory.', '2026-11-15 12:00:00+00');

INSERT INTO damage_items (id, claim_id, category, description, estimated_amount_minor) VALUES
    (101, 4601, 'BUILDING', 'Warehouse wall knocked over', 600000),
    (102, 4602, 'VEHICLE', 'Delivery van in the dock', 420000),
    (103, 4603, 'BUILDING', 'Boathouse roof caved in', 900000),
    (104, 4604, 'CONTENTS', 'Office ground floor flooded', 350000),
    (105, 4605, 'BUILDING', 'Fishing shed chewed through', 150000),
    (106, 4606, 'BUILDING', 'Shop front cracked', 500000),
    (107, 4607, 'VEHICLE', 'Parked car flattened', 320000),
    (108, 4608, 'BUILDING', 'Upper floors lost', 2000000),
    (109, 4609, 'GARDEN', 'Vineyard terraces trampled', 90000),
    (110, 4610, 'GARDEN', 'Flower beds dug up', 60000),
    (111, 4611, 'GARDEN', 'Garden furniture tipped over', 30000),
    (112, 4612, 'BUILDING', 'Conservatory smashed', 120000);

INSERT INTO payouts (id, claim_id, amount_minor, issued_at) VALUES
    (101, 4601, 580000, '2026-11-10 09:00:00+00'),
    (102, 4602, 400000, '2026-11-12 09:00:00+00'),
    (103, 4605, 140000, '2026-11-18 09:00:00+00'),
    (104, 4606, 490000, '2026-11-11 09:00:00+00'),
    (105, 4607, 300000, '2026-11-15 09:00:00+00'),
    (106, 4609, 80000, '2026-10-27 09:00:00+00'),
    (107, 4610, 40000, '2026-11-04 09:00:00+00');

-- Explicit ids do not advance the sequences: continue after the highest id (new claims still get numbers above 4713).
SELECT setval(pg_get_serial_sequence('customers', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM customers;
SELECT setval(pg_get_serial_sequence('policies', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM policies;
SELECT setval(pg_get_serial_sequence('coverages', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM coverages;
SELECT setval(pg_get_serial_sequence('claims', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM claims;
SELECT setval(pg_get_serial_sequence('damage_items', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM damage_items;
SELECT setval(pg_get_serial_sequence('payouts', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM payouts;
