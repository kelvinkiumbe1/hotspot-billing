-- A read-only evaluation ("demo") login. Any session it holds is blocked from
-- writing by DemoReadOnlyFilter, so a prospecting ISP can click through a
-- fully-populated admin without being able to change anything. Off by default;
-- only a deployment with DEMO_ENABLED=true seeds one.
ALTER TABLE staff_users
    ADD COLUMN demo BOOLEAN NOT NULL DEFAULT FALSE;
