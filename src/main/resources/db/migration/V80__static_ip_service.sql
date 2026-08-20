-- Customers who are not on PPPoE.
--
-- IPAM could already reserve a static address and write it onto the subscriber,
-- and provisionPppoe puts it on the PPP secret as remote-address -- so a PPPoE
-- customer always gets the same IP. That is a fixed address, but it is not a
-- static-IP SERVICE: PPPoE hands the address over during dial-up and the customer
-- configures nothing.
--
-- What was missing is the customer who types an address, a mask and a gateway
-- into their own router with no PPPoE at all. For them Zidi could record the
-- address and nothing else. Three consequences, and the third is the serious one:
--
--   * no speed limit, because a PPPoE customer gets theirs from a PPP profile and
--     a static customer has no profile;
--   * no way to move them between routers, since there is no secret to move;
--   * NO WAY TO CUT THEM OFF. Suspension disables a PPP secret. A static customer
--     has none, so a customer who stops paying keeps working indefinitely and
--     every dunning message goes to somebody who has no reason to read it.
--
-- That last one is a revenue hole rather than a missing feature, which is why this
-- is worth doing properly rather than as a field on a form.

-- Every existing subscriber is PPPoE, which is what the default preserves.
ALTER TABLE subscribers ADD COLUMN connection_type VARCHAR(16) NOT NULL DEFAULT 'PPPOE';

-- Needed to pin a static address to the customer's own equipment. Without it the
-- address is a suggestion: the neighbour who types it in gets the service, and
-- the customer who pays for it gets an address conflict.
ALTER TABLE subscribers ADD COLUMN mac_address VARCHAR(32);

-- Which interface a static subnet lives on, so an ARP entry can be pinned to it.
--
-- Explicit rather than derived from the VLAN id. A subnet might be on a bridge, a
-- VLAN, or a physical port, and guessing "vlan" + id produces an interface name
-- that does not exist on most boards -- which fails at provisioning time with a
-- message about ARP that tells the operator nothing.
ALTER TABLE ip_subnets ADD COLUMN interface_name VARCHAR(64);

-- The firewall list a suspended static customer goes into. Recorded per router so
-- the drop rule is only created once and can be found again.
--
-- An address list plus one drop rule, rather than deleting the queue or the ARP
-- entry. Deleting a queue removes the customer's speed LIMIT -- it does not stop
-- them, it makes them faster. Deleting an ARP entry only blocks anything if the
-- interface is set to reply-only, which is not something this code can assume
-- about somebody else's router.
CREATE TABLE static_suspend_rules (
    id          BIGSERIAL PRIMARY KEY,
    router_id   BIGINT NOT NULL UNIQUE,
    list_name   VARCHAR(64) NOT NULL,
    rule_id     VARCHAR(64),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
