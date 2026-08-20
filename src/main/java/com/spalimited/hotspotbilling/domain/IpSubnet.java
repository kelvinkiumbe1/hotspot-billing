package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A block of addresses the operator controls.
 *
 * <p>Free addresses are deliberately not rows. A /16 holds sixty-five thousand
 * of them, and pre-creating every one makes the table unusable at exactly the
 * scale where address tracking starts to matter.
 */
@Entity
@Table(name = "ip_subnets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IpSubnet {

    public enum Purpose { PPPOE, HOTSPOT, STATIC, MANAGEMENT, INFRASTRUCTURE, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String cidr;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Purpose purpose = Purpose.STATIC;

    /**
     * The router's own address in this subnet.
     *
     * <p>Reserved automatically when the subnet is created, because handing a
     * customer the gateway address takes the whole site off the air.
     */
    @Column(length = 64)
    private String gateway;

    /**
     * Which interface this subnet lives on, for pinning a static ARP entry.
     *
     * <p>Explicit rather than derived from {@link #vlanId}: a subnet might be on
     * a bridge, a VLAN or a physical port, and guessing "vlan" + id produces a
     * name that does not exist on most boards.
     */
    @Column(name = "interface_name", length = 64)
    private String interfaceName;

    private Integer vlanId;

    private Long routerId;

    private Long branchId;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
