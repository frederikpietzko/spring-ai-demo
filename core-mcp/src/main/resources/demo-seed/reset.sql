-- Demo seed set `reset` (spec demo-seed): puts the `core` database back into the migration-seeded scenario state, so
-- a demo that approved and paid claims can run again. The scenario claims 4700 and 4711 to 4713 are FILED again
-- without payouts; every claim numbered above 4713 is deleted with its damage items and payouts, except the claims of
-- the sandbox policy KP-9000 (tests write those); what the set `desk-queue` added is deleted. The claims history
-- (4601 to 4612), the fixture policies and the sequences are not touched. Idempotent.
-- Not a Flyway migration: applied only by the demo tooling. Plain statements only (no psql meta-commands, no
-- procedural blocks), so psql and a JDBC script runner run it identically.

DELETE FROM payouts
WHERE claim_id IN (4700, 4711, 4712, 4713)
   OR claim_id IN (
       SELECT c.id FROM claims c JOIN policies p ON p.id = c.policy_id
       WHERE c.id > 4713 AND p.policy_number <> 'KP-9000'
   );

DELETE FROM damage_items
WHERE claim_id IN (
    SELECT c.id FROM claims c JOIN policies p ON p.id = c.policy_id
    WHERE c.id > 4713 AND p.policy_number <> 'KP-9000'
);

DELETE FROM claims
WHERE id > 4713
  AND policy_id NOT IN (SELECT id FROM policies WHERE policy_number = 'KP-9000');

UPDATE claims SET status = 'FILED' WHERE id IN (4700, 4711, 4712, 4713);

-- What `desk-queue` added: its claims (numbered above 4713) are gone already; now its policies and customers.
DELETE FROM coverages
WHERE policy_id IN (SELECT id FROM policies WHERE policy_number IN ('KP-3001', 'KP-3002', 'KP-3003'));

DELETE FROM policies WHERE policy_number IN ('KP-3001', 'KP-3002', 'KP-3003');

DELETE FROM customers WHERE id IN (201, 202, 203);
