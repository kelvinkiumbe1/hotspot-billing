package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How agent commission gets paid out. Single row (id = 1). Off by default:
 * this sends real money, and nobody should discover that by surprise.
 */
@Entity
@Table(name = "agent_payout_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentPayoutSettings {

    public static final long SINGLETON_ID = 1L;

    public enum Frequency { WEEKLY, MONTHLY }

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /**
     * Whether a prepared payout goes out on its own. With this off, the run
     * still works out who is owed what and queues it — a human just presses
     * the button. That is the sensible first month.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean autoSend = false;

    /** Below this the balance rolls over; a tiny transfer costs more in fees. */
    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumAmount = new BigDecimal("500");

    /** A ceiling on one run, so a mistake cannot empty the float in one pass. */
    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal maxPerRun = new BigDecimal("20000");

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Frequency frequency = Frequency.WEEKLY;

    /** 1 = Monday … 7 = Sunday, when the frequency is weekly. */
    @Builder.Default
    @Column(nullable = false)
    private int dayOfWeek = 1;

    /** Clamped to the length of the month, so the 31st still pays in February. */
    @Builder.Default
    @Column(nullable = false)
    private int dayOfMonth = 1;

    @Builder.Default
    @Column(nullable = false)
    private int runHour = 9;

    /** Blank means pay from the same shortcode that collects. */
    @Column(length = 20)
    private String b2cShortCode;

    /** The last day a run happened, so a restart cannot double-pay. */
    private LocalDate lastRunOn;
}
