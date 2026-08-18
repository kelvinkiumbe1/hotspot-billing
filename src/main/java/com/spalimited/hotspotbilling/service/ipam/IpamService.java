package com.spalimited.hotspotbilling.service.ipam;

import com.spalimited.hotspotbilling.domain.IpAssignment;
import com.spalimited.hotspotbilling.domain.IpSubnet;
import com.spalimited.hotspotbilling.repository.IpAssignmentRepository;
import com.spalimited.hotspotbilling.repository.IpSubnetRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which addresses exist, which are taken, and which one to hand out next.
 *
 * <p>The last of the table-stakes gaps against Splynx, Sonar and UISP. Until
 * now addresses lived in a spreadsheet or in somebody's head, which works right
 * up to the day two customers are given the same one — and that arrives as an
 * intermittent outage nobody can reproduce rather than as an error.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IpamService {

    /**
     * How far to walk looking for a free address before giving up.
     *
     * <p>A /8 holds sixteen million addresses. Scanning all of them to answer
     * "is anything free" would hang the request that asked, so a subnet that
     * dense is reported as full rather than searched to the end. In practice
     * nothing an ISP hands out by hand is remotely this large.
     */
    private static final int SEARCH_LIMIT = 65_536;

    private final IpSubnetRepository subnets;
    private final IpAssignmentRepository assignments;
    private final SubscriberRepository subscribers;

    // --- Subnets ---

    /**
     * Adds a subnet, refusing one that overlaps an existing one.
     *
     * <p>Overlap is the mistake worth blocking: two subnets sharing addresses
     * means the allocator believes an address is free in one while it is live
     * in the other, and it hands it out.
     */
    @Transactional
    public IpSubnet create(IpSubnet incoming, String by) {
        Cidr cidr = Cidr.parse(incoming.getCidr());
        for (IpSubnet existing : subnets.findAll()) {
            Cidr other = Cidr.parse(existing.getCidr());
            if (cidr.overlaps(other)) {
                throw new IllegalArgumentException(cidr + " overlaps " + existing.getName()
                        + " (" + other + "). Two subnets sharing addresses means the same "
                        + "address gets handed out twice.");
            }
        }
        // Stored normalised, so 10.20.0.5/24 and 10.20.0.0/24 cannot both exist.
        incoming.setCidr(cidr.toString());

        String gateway = incoming.getGateway();
        if (gateway != null && !gateway.isBlank() && !cidr.contains(gateway)) {
            throw new IllegalArgumentException("The gateway " + gateway + " is not inside " + cidr);
        }
        IpSubnet saved = subnets.save(incoming);

        // The gateway is taken out of the pool immediately. Handing a customer
        // the router's own address takes the whole site off the air.
        if (gateway != null && !gateway.isBlank()) {
            assignments.save(IpAssignment.builder()
                    .subnetId(saved.getId())
                    .address(gateway)
                    .kind(IpAssignment.Kind.GATEWAY)
                    .notes("The router. Reserved automatically.")
                    .assignedBy(by)
                    .build());
        }
        return saved;
    }

    @Transactional
    public void delete(Long subnetId) {
        assignments.deleteBySubnetId(subnetId);
        subnets.deleteById(subnetId);
    }

    // --- Allocation ---

    /**
     * The next address nobody holds, or empty when the subnet is full.
     *
     * <p>Walks the range rather than materialising it, and reads the taken
     * addresses once into a set — asking the database per candidate turns
     * allocating one address in a /22 into a thousand queries.
     */
    @Transactional(readOnly = true)
    public Optional<String> nextFree(Long subnetId) {
        IpSubnet subnet = require(subnetId);
        Cidr cidr = Cidr.parse(subnet.getCidr());
        Set<Long> taken = takenIn(subnetId);

        long first = cidr.firstUsable();
        long last = cidr.lastUsable();
        long ceiling = Math.min(last, first + SEARCH_LIMIT - 1);
        for (long candidate = first; candidate <= ceiling; candidate++) {
            if (!taken.contains(candidate)) {
                return Optional.of(Cidr.toAddress(candidate));
            }
        }
        return Optional.empty();
    }

    /**
     * Takes a specific address, or the next free one when none is asked for.
     *
     * <p>The uniqueness constraint is in the database as well as here, and the
     * violation is caught rather than avoided: two staff members allocating at
     * the same moment both see the address as free, and only the constraint
     * knows which of them got it.
     */
    @Transactional
    public IpAssignment assign(Long subnetId, String wanted, IpAssignment.Kind kind,
                               Long subscriberId, Long deviceId, String hostname,
                               String macAddress, String notes, String by) {
        IpSubnet subnet = require(subnetId);
        Cidr cidr = Cidr.parse(subnet.getCidr());

        String address;
        if (wanted != null && !wanted.isBlank()) {
            address = wanted.trim();
            if (!cidr.contains(address)) {
                throw new IllegalArgumentException(address + " is not inside " + cidr);
            }
            long value = Cidr.toLong(address);
            if (value < cidr.firstUsable() || value > cidr.lastUsable()) {
                // The network and broadcast addresses are not hosts. Assigning
                // one produces a customer who cannot get online and a fault
                // that looks like anything but this.
                throw new IllegalArgumentException(address + " is the "
                        + (value == cidr.networkAddress() ? "network" : "broadcast")
                        + " address of " + cidr + ", not a usable host address");
            }
        } else {
            address = nextFree(subnetId).orElseThrow(() -> new IllegalStateException(
                    subnet.getName() + " (" + cidr + ") has no free addresses left"));
        }

        IpAssignment assignment = IpAssignment.builder()
                .subnetId(subnetId)
                .address(address)
                .kind(kind == null ? IpAssignment.Kind.ASSIGNED : kind)
                .subscriberId(subscriberId)
                .deviceId(deviceId)
                .hostname(blankToNull(hostname))
                .macAddress(blankToNull(macAddress))
                .notes(blankToNull(notes))
                .assignedBy(by)
                .build();
        try {
            IpAssignment saved = assignments.save(assignment);
            // A subscriber's static address is mirrored onto their record so
            // RADIUS can hand it out at login without a second lookup.
            if (subscriberId != null) {
                subscribers.findById(subscriberId).ifPresent(sub -> {
                    sub.setStaticIp(saved.getAddress());
                    subscribers.save(sub);
                });
            }
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(address + " was taken a moment ago by somebody else. "
                    + "Try again and you will be offered the next free one.");
        }
    }

    @Transactional
    public void release(Long assignmentId) {
        IpAssignment assignment = assignments.findById(assignmentId).orElseThrow(() ->
                new IllegalArgumentException("No such assignment"));
        if (assignment.getKind() == IpAssignment.Kind.GATEWAY) {
            throw new IllegalArgumentException("That is the router's own address. Releasing it "
                    + "would let it be handed to a customer and take the site down.");
        }
        if (assignment.getSubscriberId() != null) {
            subscribers.findById(assignment.getSubscriberId()).ifPresent(sub -> {
                if (assignment.getAddress().equals(sub.getStaticIp())) {
                    sub.setStaticIp(null);
                    subscribers.save(sub);
                }
            });
        }
        // Deleted rather than flagged. A released-but-present row is the state
        // where a query forgets the flag and hands out a live address.
        assignments.delete(assignment);
    }

    // --- Reading ---

    /** How full a subnet is, without counting sixteen million addresses. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(IpSubnet subnet) {
        Cidr cidr = Cidr.parse(subnet.getCidr());
        long usable = cidr.usableCount();
        long used = assignments.countBySubnetId(subnet.getId());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", subnet.getId());
        row.put("name", subnet.getName());
        row.put("cidr", subnet.getCidr());
        row.put("purpose", subnet.getPurpose().name());
        row.put("gateway", subnet.getGateway());
        row.put("vlanId", subnet.getVlanId());
        row.put("routerId", subnet.getRouterId());
        row.put("description", subnet.getDescription());
        row.put("firstUsable", Cidr.toAddress(cidr.firstUsable()));
        row.put("lastUsable", Cidr.toAddress(cidr.lastUsable()));
        row.put("usable", usable);
        row.put("used", used);
        row.put("free", Math.max(0, usable - used));
        // Rounded down deliberately: a subnet at 99.6% should not read as 100%
        // full while an address is still available.
        row.put("percentUsed", usable == 0 ? 100 : (int) Math.min(100, used * 100 / usable));
        return row;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IpSubnet subnet : subnets.findAllByOrderByNameAsc()) {
            out.add(describe(subnet));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<IpAssignment> assignmentsIn(Long subnetId) {
        return assignments.findBySubnetIdOrderByAddressAsc(subnetId);
    }

    /**
     * Every subnet that is nearly out of addresses.
     *
     * <p>Worth surfacing rather than waiting to be asked: running out is not a
     * gradual problem, it is a day when the next install cannot be done.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> nearlyFull(int threshold) {
        return all().stream()
                .filter(s -> (Integer) s.get("percentUsed") >= threshold)
                .toList();
    }

    private Set<Long> takenIn(Long subnetId) {
        Set<Long> taken = new HashSet<>();
        for (IpAssignment assignment : assignments.findBySubnetIdOrderByAddressAsc(subnetId)) {
            try {
                taken.add(Cidr.toLong(assignment.getAddress()));
            } catch (IllegalArgumentException e) {
                // A malformed stored address should not stop allocation; it just
                // cannot be compared against, so it is skipped and logged.
                log.warn("Assignment {} holds an unreadable address {}",
                        assignment.getId(), assignment.getAddress());
            }
        }
        return taken;
    }

    private IpSubnet require(Long subnetId) {
        return subnets.findById(subnetId).orElseThrow(() ->
                new IllegalArgumentException("No such subnet"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
