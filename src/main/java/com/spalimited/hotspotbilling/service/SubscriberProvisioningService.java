package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.IpAssignment;
import com.spalimited.hotspotbilling.domain.IpSubnet;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.IpAssignmentRepository;
import com.spalimited.hotspotbilling.repository.IpSubnetRepository;
import com.spalimited.hotspotbilling.service.ipam.Cidr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Putting a customer on a router, whichever kind of customer they are.
 *
 * <h2>Why this exists rather than an if-statement in sixteen places</h2>
 *
 * <p>Sixteen call sites across five services called provisionPppoe,
 * setPppoeEnabled, setPppoeRate or removePppoe directly. Adding a second
 * connection type by branching at each of them would mean sixteen chances to
 * forget one -- and the one forgotten is discovered as a static customer who
 * cannot be suspended, which is to say a customer who stops paying and keeps
 * working.
 *
 * <p>So the branch lives here, once, and every caller asks for the outcome
 * instead of the mechanism: provision this customer, cut this customer off, set
 * this customer's speed.
 *
 * <h2>The two kinds are genuinely different</h2>
 *
 * <p>PPPoE is a session: the router dials in, gets an address and a speed from a
 * profile, and disabling the secret ends it. Static has no session at all -- the
 * customer typed an address into their own equipment -- so the speed is a queue
 * against that address and cutting them off is a firewall rule. A change to a
 * PPPoE customer's speed needs a reconnect; a change to a static customer's does
 * not, because a queue applies to traffic rather than to a session.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriberProvisioningService {

    private final MikrotikService mikrotikService;
    private final IpAssignmentRepository assignments;
    private final IpSubnetRepository subnets;

    /** Sets a customer up, or brings them back into line with their record. */
    @Transactional
    public void provision(Subscriber sub) {
        if (sub.isStatic()) {
            mikrotikService.provisionStatic(sub, placementFor(sub));
        } else {
            mikrotikService.provisionPppoe(sub);
        }
    }

    /** Cuts a customer off, or lets them back on. */
    @Transactional
    public void setEnabled(Subscriber sub, boolean enabled) {
        if (sub.isStatic()) {
            mikrotikService.setStaticEnabled(sub, placementFor(sub), enabled);
        } else {
            mikrotikService.setPppoeEnabled(sub, enabled);
        }
    }

    /** Changes a customer's speed. Null restores their normal bandwidth. */
    @Transactional
    public void setRate(Subscriber sub, String rate) {
        if (sub.isStatic()) {
            mikrotikService.setStaticRate(sub, rate);
        } else {
            mikrotikService.setPppoeRate(sub, rate);
        }
    }

    /** Takes a customer off the router entirely. */
    @Transactional
    public void remove(Subscriber sub) {
        if (sub.isStatic()) {
            mikrotikService.removeStatic(sub, placementFor(sub));
        } else {
            mikrotikService.removePppoe(sub);
        }
    }

    /**
     * Whether a speed change needs the customer to reconnect before it applies.
     *
     * <p>Asked by the admin so it can say which happened. RouterOS applies a PPP
     * profile at dial-in, so a PPPoE customer keeps their old speed until they
     * redial; a queue applies to traffic, so a static customer's changes at once.
     * Telling a customer the wrong one produces a support call either way.
     */
    public boolean rateChangeNeedsReconnect(Subscriber sub) {
        return !sub.isStatic();
    }

    /**
     * Where a static customer sits: address, mask, gateway and interface.
     *
     * <p>Null when they have no allocation, which the caller reports rather than
     * guessing a mask -- a /24 assumed onto a /22 subnet gives the customer a
     * gateway they cannot reach and looks exactly like a dead link.
     */
    @Transactional(readOnly = true)
    public MikrotikService.StaticPlacement placementFor(Subscriber sub) {
        if (sub.getStaticIp() == null || sub.getStaticIp().isBlank()) {
            return null;
        }
        IpSubnet subnet = subnetFor(sub);
        if (subnet == null) {
            return null;
        }
        Cidr range = Cidr.parse(subnet.getCidr());
        return new MikrotikService.StaticPlacement(
                sub.getStaticIp().trim(),
                range.prefix(),
                subnet.getGateway(),
                subnet.getInterfaceName(),
                sub.getMacAddress());
    }

    /**
     * The settings to read down the phone, or print on a job sheet.
     *
     * <p>The whole point of a static service from the customer's side: four
     * values they type into their own router. Assembled from the subnet rather
     * than typed by whoever is on the call, because a gateway remembered wrongly
     * is an installation that fails and a van that goes back out.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> customerSettings(Subscriber sub) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectionType", sub.getConnectionType());
        if (!sub.isStatic()) {
            out.put("usable", false);
            out.put("note", "This customer is on PPPoE — their router dials in with a username "
                    + "and password, and the address is handed to it. There is nothing to "
                    + "type in.");
            return out;
        }
        if (sub.getStaticIp() == null || sub.getStaticIp().isBlank()) {
            out.put("usable", false);
            out.put("note", "No address allocated yet. Give them one under Addresses.");
            return out;
        }
        IpSubnet subnet = subnetFor(sub);
        if (subnet == null) {
            out.put("usable", false);
            out.put("note", "Their address " + sub.getStaticIp() + " is not in any subnet this "
                    + "system knows, so the mask and gateway cannot be worked out.");
            return out;
        }
        Cidr range = Cidr.parse(subnet.getCidr());
        out.put("usable", true);
        out.put("ipAddress", sub.getStaticIp());
        out.put("subnetMask", maskOf(range.prefix()));
        out.put("prefix", range.prefix());
        out.put("gateway", subnet.getGateway());
        // The gateway doubles as the resolver on almost every MikroTik, and an
        // operator who runs something else can say so. Offering a guess is better
        // than leaving the field blank, which gets filled in with 8.8.8.8 and
        // then blamed on us when it is blocked upstream.
        out.put("dns", subnet.getGateway());
        out.put("vlanId", subnet.getVlanId());
        out.put("macAddress", sub.getMacAddress());
        out.put("pinnedToDevice", sub.getMacAddress() != null && !sub.getMacAddress().isBlank());
        return out;
    }

    /** The subnet an address falls inside. */
    private IpSubnet subnetFor(Subscriber sub) {
        // The assignment knows its subnet directly, which is both cheaper and
        // right when two subnets overlap -- which they should not, and sometimes
        // do.
        for (IpAssignment a : assignments.findBySubscriberId(sub.getId())) {
            if (sub.getStaticIp().equals(a.getAddress())) {
                IpSubnet found = subnets.findById(a.getSubnetId()).orElse(null);
                if (found != null) {
                    return found;
                }
            }
        }
        // No assignment row: the address was typed onto the subscriber by hand.
        // Falling back to a containment search means that still works.
        for (IpSubnet subnet : subnets.findAll()) {
            try {
                if (Cidr.parse(subnet.getCidr()).contains(sub.getStaticIp())) {
                    return subnet;
                }
            } catch (RuntimeException badCidr) {
                log.debug("Skipping unparseable subnet {}", subnet.getCidr());
            }
        }
        return null;
    }

    /** A prefix length as the dotted mask a customer's router asks for. */
    static String maskOf(int prefix) {
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return ((mask >> 24) & 0xFF) + "." + ((mask >> 16) & 0xFF) + "."
                + ((mask >> 8) & 0xFF) + "." + (mask & 0xFF);
    }
}
