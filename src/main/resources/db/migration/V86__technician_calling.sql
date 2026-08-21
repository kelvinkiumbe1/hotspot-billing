-- Letting a field technician ring a customer from the business number.
--
-- The call centre was built for the office. A technician standing at a pole
-- still taps a tel: link, which dials from their personal handset and shows the
-- customer their personal number -- exactly the problem the virtual number was
-- bought to solve, still unsolved for the people who make the most calls.

ALTER TABLE call_agents
    ADD COLUMN technician_id BIGINT REFERENCES technicians(id) ON DELETE CASCADE,
    -- Whether this agent is in the rotation for calls coming IN.
    --
    -- A technician needs to place calls without joining the support queue: a
    -- customer ringing the business should not reach whoever happens to be up a
    -- ladder. Existing agents are all office staff, so they keep the old
    -- behaviour by default and only technician-backed rows opt out.
    ADD COLUMN inbound BOOLEAN NOT NULL DEFAULT TRUE;

-- One agent row per technician, so repeated calls reuse it rather than filling
-- the rota with duplicates of the same person.
CREATE UNIQUE INDEX uq_call_agents_technician
    ON call_agents (technician_id) WHERE technician_id IS NOT NULL;

CREATE INDEX idx_call_agents_inbound ON call_agents (inbound, active, priority);
