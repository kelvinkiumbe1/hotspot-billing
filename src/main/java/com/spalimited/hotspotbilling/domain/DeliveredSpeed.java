package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * What a customer actually got, as against what they pay for.
 *
 * <p>Every plan sells a speed and nothing has ever checked whether it arrives.
 * That cuts both ways: a customer paying for 10M and receiving 2M leaves
 * without ever saying why, and an operator accused of throttling has had no
 * way to answer. Both need the same measurement.
 */
@Entity
@Table(name = "delivered_speed")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveredSpeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long subscriberId;

    @Column(nullable = false)
    private LocalDate observedOn;

    /**
     * The busiest sample seen that day, in bits per second.
     *
     * <p>A peak rather than an average, because an average across a day is
     * mostly the customer being asleep and says nothing at all about what they
     * get when they actually use it.
     */
    @Builder.Default
    @Column(nullable = false)
    private long peakDownBps = 0;

    @Builder.Default
    @Column(nullable = false)
    private long peakUpBps = 0;

    /** What they had bought at the time — plans change, and history must not. */
    private Long planDownBps;

    private Long planUpBps;

    @Builder.Default
    @Column(nullable = false)
    private int samples = 0;

    /**
     * How much of the sold speed actually turned up, 0-100, or null when there
     * is nothing to compare against.
     *
     * <p>Capped at 100: a burst allowance can genuinely exceed the plan, and
     * reporting 140% would look like a bug rather than a feature.
     */
    @Transient
    public Integer getDeliveredPercent() {
        if (planDownBps == null || planDownBps <= 0 || samples == 0) {
            return null;
        }
        return (int) Math.min(100, peakDownBps * 100 / planDownBps);
    }

    /**
     * Whether this day is evidence of a real shortfall.
     *
     * <p>Two guards, both to avoid crying wolf. A customer who never asked for
     * much cannot be shown to have been short-changed — a peak of 1M on a 10M
     * line may be all they wanted. And a single quiet day proves nothing, which
     * is why the report needs several before it says anything.
     */
    @Transient
    public boolean isShortfall() {
        Integer delivered = getDeliveredPercent();
        return delivered != null && delivered < 50 && samples >= 20;
    }
}
