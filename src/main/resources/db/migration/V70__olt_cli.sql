-- Provisioning an ONU: typing at the OLT.
--
-- The only thing in this system that writes to a device where a mistake is
-- measured in streets. A wrong SNMP OID returns nothing and a wrong payment field
-- gets a refusal; a wrong command on an OLT can deauthorise a PON port and darken
-- several hundred houses, and the OLT will do it without asking twice.
--
-- So nothing is sent until it has been shown. The service builds the exact
-- commands and returns them; applying them is a separate call an operator has to
-- make on purpose, and every command sent is audited in full before the attempt
-- rather than after -- an audit trail written only on success is missing exactly
-- the entries somebody will be looking for.
--
-- Telnet, because these boxes offer it and no SSH client is available to this
-- build. The credentials below therefore cross the management network in the
-- clear. That is a real limitation rather than a detail: an OLT belongs on an
-- isolated management VLAN, and the admin says so where the password is entered.

ALTER TABLE network_devices ADD COLUMN cli_username VARCHAR(120);
ALTER TABLE network_devices ADD COLUMN cli_password VARCHAR(120);
ALTER TABLE network_devices ADD COLUMN cli_port     INTEGER;
