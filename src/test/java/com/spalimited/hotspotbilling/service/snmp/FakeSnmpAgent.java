package com.spalimited.hotspotbilling.service.snmp;

import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * A switch, for the length of one test.
 *
 * <p>Answers real SNMP over a real UDP socket on localhost, which is the only
 * way to find out whether {@link SnmpClient} genuinely speaks the protocol.
 * Everything short of this — mocking the client, asserting on OID constants —
 * proves the test author's beliefs about SNMP rather than the code's.
 *
 * <p>Deliberately minimal: GET for scalars, GETNEXT for walks. That is all a
 * v2c poll of an interface table ever issues.
 */
class FakeSnmpAgent implements AutoCloseable, CommandResponder {

    private final Snmp snmp;
    private final TreeMap<OID, Variable> tree = new TreeMap<>();
    private final String community;
    private final int port;

    FakeSnmpAgent(String community) throws IOException {
        this.community = community;
        // Port 0 lets the OS pick a free one, so a developer already running
        // something on 161 does not get a mysterious failure.
        DefaultUdpTransportMapping transport =
                new DefaultUdpTransportMapping(new UdpAddress("127.0.0.1/0"));
        this.snmp = new Snmp(transport);
        snmp.addCommandResponder(this);
        snmp.listen();
        this.port = transport.getListenAddress().getPort();
    }

    int port() {
        return port;
    }

    FakeSnmpAgent set(String oid, Variable value) {
        tree.put(new OID(oid), value);
        return this;
    }

    /** One row of the interface table, in the columns SnmpClient reads. */
    FakeSnmpAgent addPort(int index, String descr, String name, String alias,
                          int adminStatus, int operStatus, long speed,
                          long inOctets, long outOctets, long inErrors, long outErrors) {
        set(SnmpClient.IF_DESCR + "." + index, new OctetString(descr));
        set(SnmpClient.IF_SPEED + "." + index, new Gauge32(speed));
        set(SnmpClient.IF_ADMIN_STATUS + "." + index, new Integer32(adminStatus));
        set(SnmpClient.IF_OPER_STATUS + "." + index, new Integer32(operStatus));
        set(SnmpClient.IF_IN_OCTETS + "." + index, new Counter32(inOctets));
        set(SnmpClient.IF_IN_ERRORS + "." + index, new Counter32(inErrors));
        set(SnmpClient.IF_OUT_OCTETS + "." + index, new Counter32(outOctets));
        set(SnmpClient.IF_OUT_ERRORS + "." + index, new Counter32(outErrors));
        set(SnmpClient.IF_NAME + "." + index, new OctetString(name));
        set(SnmpClient.IF_ALIAS + "." + index, new OctetString(alias));
        return this;
    }

    /** ifXTable's 64-bit counters, which real gear offers and old gear does not. */
    FakeSnmpAgent addHighCapacity(int index, long hcIn, long hcOut, long speedMbps) {
        set(SnmpClient.IF_HC_IN_OCTETS + "." + index, new Counter64(hcIn));
        set(SnmpClient.IF_HC_OUT_OCTETS + "." + index, new Counter64(hcOut));
        set(SnmpClient.IF_HIGH_SPEED + "." + index, new Gauge32(speedMbps));
        return this;
    }

    @Override
    public void processPdu(CommandResponderEvent event) {
        PDU request = event.getPDU();
        if (request == null) {
            return;
        }
        if (!new OctetString(community).equals(new OctetString(event.getSecurityName()))) {
            return; // wrong community: a real agent simply says nothing
        }

        PDU response = new PDU();
        response.setType(PDU.RESPONSE);
        response.setRequestID(request.getRequestID());

        for (VariableBinding incoming : request.getVariableBindings()) {
            OID oid = incoming.getOid();
            if (request.getType() == PDU.GETNEXT) {
                Map.Entry<OID, Variable> next = tree.higherEntry(oid);
                response.add(next == null
                        ? new VariableBinding(oid, Null.endOfMibView)
                        : new VariableBinding(next.getKey(), next.getValue()));
            } else {
                Variable value = tree.get(oid);
                response.add(value == null
                        ? new VariableBinding(oid, Null.noSuchObject)
                        : new VariableBinding(oid, value));
            }
        }

        try {
            event.getMessageDispatcher().returnResponsePdu(
                    event.getMessageProcessingModel(), event.getSecurityModel(),
                    event.getSecurityName(), event.getSecurityLevel(), response,
                    event.getMaxSizeResponsePDU(), event.getStateReference(), null);
        } catch (Exception e) {
            throw new IllegalStateException("fake agent could not reply", e);
        }
        event.setProcessed(true);
    }

    @Override
    public void close() throws IOException {
        snmp.close();
    }

    static {
        // Without this the dispatcher has no v2c message processing model and
        // silently drops every request, which looks exactly like a timeout.
        org.snmp4j.security.SecurityProtocols.getInstance().addDefaultProtocols();
    }

    static final int VERSION_2C = SnmpConstants.version2c;
}
