-- A pass that works at more than one site, and moving customers between routers.
--
-- ROAMING. provisionVoucher pushed a hotspot user to defaultRouter() and nowhere
-- else, so a code sold at one site simply did not exist at any other. A customer
-- who walks into the operator's second shop is told their voucher is invalid,
-- which is indistinguishable from a fake code and gets treated as one.
--
-- With roaming on, a voucher is pushed to every router the operator manages, and
-- removed from all of them when it expires. The cost is real and worth stating:
-- provisioning becomes N API calls instead of one, and a router that is down at
-- the moment a code is sold will not have it until the next sweep. So it is a
-- setting rather than the default -- an operator with one site gains nothing and
-- pays for every voucher.
--
-- The other half is knowing which routers a pass reached. Without it, a router
-- that was down during provisioning is indistinguishable from one that was never
-- meant to have the code, and nothing can repair the gap.

ALTER TABLE mikrotik_settings ADD COLUMN roaming_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Which routers a voucher was actually pushed to, as a comma-separated list of
-- ids.
--
-- A column rather than a join table on purpose. This is read only to repair gaps
-- and is written on every provision; a row per (voucher, router) on a network
-- selling ten thousand codes a month across six sites is sixty thousand rows a
-- month to answer a question asked by one sweep.
ALTER TABLE vouchers ADD COLUMN pushed_router_ids VARCHAR(500);

-- FLEET MOVES. Replacing a dead router or moving a batch of customers to a new
-- one both mean touching every affected subscriber, and both can half-fail: the
-- new router accepts twelve of twenty and then stops answering. Recording the
-- attempt is what makes the remaining eight findable rather than a discrepancy
-- somebody notices weeks later.
CREATE TABLE router_moves (
    id             BIGSERIAL PRIMARY KEY,
    kind           VARCHAR(24) NOT NULL,
    from_router_id BIGINT,
    to_router_id   BIGINT NOT NULL,
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at    TIMESTAMP WITH TIME ZONE,
    moved_count    INTEGER NOT NULL DEFAULT 0,
    failed_count   INTEGER NOT NULL DEFAULT 0,
    -- Which customers did not make it, and why. The point of the whole table.
    detail         VARCHAR(4000),
    started_by     VARCHAR(120)
);

CREATE INDEX idx_router_moves_started ON router_moves (started_at DESC);
