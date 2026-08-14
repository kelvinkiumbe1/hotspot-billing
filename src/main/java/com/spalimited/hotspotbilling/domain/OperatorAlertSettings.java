package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * How the operator wants to be kept informed: router-down alerts, a daily
 * sales digest, and whether paid subscribers are automatically compensated
 * for network downtime. Single row (id = 1). The alerts and digest go to
 * the alert phone / email configured under messaging and email settings.
 */
@Entity
@Table(name = "operator_alert_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorAlertSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Text the operator when a router drops offline (and again when it recovers). */
    @Builder.Default
    @Column(nullable = false)
    private boolean routerOfflineAlert = true;

    /** After an outage, push every active subscriber's expiry back by the downtime. */
    @Builder.Default
    @Column(nullable = false)
    private boolean outageCompensationEnabled = false;

    /** Ignore blips: only compensate outages at least this many minutes long. */
    @Builder.Default
    @Column(nullable = false)
    private int minOutageMinutes = 30;

    /** Send a once-a-day summary of sales to the operator. */
    @Builder.Default
    @Column(nullable = false)
    private boolean salesDigestEnabled = false;

    /** Hour of day (0–23, server time) to send the digest. */
    @Builder.Default
    @Column(nullable = false)
    private int salesDigestHour = 20;

    /** The last day a digest went out, so a restart can't double-send. */
    private LocalDate lastDigestSent;

    // --- Outage incidents ---

    /**
     * Tell affected customers when the network is down, rather than leaving
     * them to work it out and call. Off by default: it puts a message in front
     * of paying customers, which is the operator's decision to make.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean customerOutageNotice = false;

    /** Wait this long before telling anyone — most blips fix themselves. */
    @Builder.Default
    @Column(nullable = false)
    private int outageNotifyAfterMinutes = 10;

    /** The estimate given in that message, in minutes. */
    @Builder.Default
    @Column(nullable = false)
    private int outageEtaMinutes = 120;

    /** Publish current and recent incidents on a public status page. */
    @Builder.Default
    @Column(nullable = false)
    private boolean statusPageEnabled = true;
}
