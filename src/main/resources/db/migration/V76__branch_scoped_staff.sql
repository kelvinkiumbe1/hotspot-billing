-- A login that can only see one branch.
--
-- The data was already partitioned: subscribers.branch_id and routers.branch_id
-- have existed for a while, and the Branches screen manages them. What was
-- missing was the other half -- staff_users had no branch at all, so every login
-- saw every branch's customers. A franchise, a partner reselling in another
-- town, or a manager who should only see their own site all had the same access
-- as head office.
--
-- NULL means head office: sees everything, which is what every existing login
-- keeps. A branch id is a restriction, never a grant, so this column can only
-- ever narrow what somebody can reach.
--
-- FAIL CLOSED, and this is the part worth understanding before extending it.
--
-- Retrofitting a filter onto sixty-odd endpoints one at a time guarantees that
-- some of them get missed, and a branch manager who sees their own customers on
-- nine screens and everybody's on the tenth is worse than one who is simply
-- refused -- because nobody discovers the tenth screen until a partner has read
-- a competitor's customer list.
--
-- So BranchScopeFilter works the other way round: a branch-scoped session may
-- reach only the paths on an explicit allowlist, and everything else is refused
-- with a message saying why. Widening access is then a deliberate edit to one
-- readable list, and the failure mode of forgetting is a locked door rather than
-- an open one.

ALTER TABLE staff_users ADD COLUMN branch_id BIGINT REFERENCES branches(id) ON DELETE SET NULL;

-- ON DELETE SET NULL deserves a note, because the obvious reading is wrong.
-- Deleting a branch promotes its staff to head office, which sounds alarming --
-- but the alternative, ON DELETE CASCADE, would silently delete people's logins,
-- and a NULL is visible on the Staff screen as "all branches" where somebody
-- will notice it. Neither is automatic; this one is at least loud.

CREATE INDEX idx_staff_users_branch ON staff_users (branch_id);
