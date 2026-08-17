package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A limited-time offer, e.g. "Weekend Offer — 20% off". While the current
 * time is inside [startsAt, endsAt) every purchase price is discounted and
 * the portal shows the banner. Prices are computed on the fly, so when the
 * window ends everything reverts automatically — original plan prices are
 * never modified.
 */
@Entity
@Table(name = "promotions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Banner text, e.g. "Enjoy the Weekend Offer!". */
    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int discountPercent;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Who opened this offer. A promotion the operator started by hand is
     * theirs: the off-peak scheduler may only close the ones it opened, or it
     * would end a weekend sale the moment it decided the quiet hours were over.
     */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String source = "MANUAL";

    public static final String SOURCE_OFFPEAK = "OFFPEAK";

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
