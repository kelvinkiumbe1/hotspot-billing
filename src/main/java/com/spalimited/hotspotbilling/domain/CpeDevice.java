package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A router in somebody's house, as TR-069 knows it.
 *
 * <p>Created by the device rather than by an operator: a CPE pointed at this ACS
 * introduces itself with an Inform and this row appears. That is the whole point
 * of the protocol -- an ISP ships a box, the box calls home, and nobody has to
 * type its serial number anywhere.
 */
@Entity
@Table(name = "cpe_devices",
        uniqueConstraints = @UniqueConstraint(columnNames = {"oui", "serial_number"}),
        indexes = {
                @Index(name = "cpe_devices_subscriber_idx", columnList = "subscriber_id"),
                @Index(name = "cpe_devices_last_inform_idx", columnList = "last_inform_at"),
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CpeDevice {

    /**
     * Which data model the device speaks.
     *
     * <p>The single most consequential fact about a CPE, because every parameter
     * path differs between them: the WiFi password is
     * InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.KeyPassphrase on TR-098
     * and Device.WiFi.AccessPoint.1.Security.KeyPassphrase on TR-181. Guess wrong
     * and the device answers with a fault, or worse accepts and ignores it.
     */
    public enum DataModel { TR098, TR181, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The manufacturer's OUI. Half of the identity TR-069 guarantees is unique. */
    @Column(nullable = false, length = 16)
    private String oui;

    @Column(name = "serial_number", nullable = false, length = 64)
    private String serialNumber;

    @Column(length = 120)
    private String manufacturer;

    @Column(length = 120)
    private String productClass;

    @Column(length = 80)
    private String softwareVersion;

    @Column(length = 80)
    private String hardwareVersion;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DataModel dataModel = DataModel.UNKNOWN;

    /**
     * Where to poke the device to make it call in now.
     *
     * <p>Without this a change waits for the periodic Inform, which is typically
     * an hour -- long enough that an operator on the phone to a customer gives up
     * and talks them through the web interface instead.
     */
    @Column(length = 300)
    private String connectionRequestUrl;

    /** Credentials the CPE demands on a connection request. Digest, usually. */
    @Column(length = 120)
    private String connectionRequestUsername;

    @Column(length = 120)
    private String connectionRequestPassword;

    /** Where it called from, which is not where it lives on the LAN. */
    @Column(length = 64)
    private String remoteAddress;

    private Instant lastInformAt;

    /** Why it called: BOOT, PERIODIC, VALUE CHANGE, CONNECTION REQUEST. */
    @Column(length = 80)
    private String lastEvent;

    /**
     * The customer this box belongs to, once somebody has said so.
     *
     * <p>Nullable and stays that way until matched. A CPE knows its serial and
     * billing knows customers; nothing joins the two except a person or a
     * matching stock record, so this is never guessed.
     */
    private Long subscriberId;

    @Column(length = 1000)
    private String notes;

    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
