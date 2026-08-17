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
 * How field work runs itself: technicians working their jobs from WhatsApp,
 * and the system chasing the jobs nobody has picked up or touched.
 * Single row (id = 1).
 */
@Entity
@Table(name = "field_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /**
     * Technicians can run jobs from WhatsApp. Recognised by the phone number on
     * their technician record, so a technician with no number simply carries on
     * using the app.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean whatsappEnabled = true;

    /**
     * A job that has been assigned this long with no note and no movement gets
     * a nudge. This is the common failure: the job was accepted, the customer
     * was told someone is coming, and then nothing.
     */
    @Builder.Default
    @Column(nullable = false)
    private int staleJobHours = 4;

    /**
     * Tell the technicians as soon as a customer opens a ticket, rather than
     * only once somebody has been assigned to it. Without this, a ticket
     * raised out of hours waits for an operator to notice it — the technicians
     * could always pull it from the queue, but nothing told them it was there.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean notifyTechniciansOnNewTicket = true;

    /** Open with nobody on it for this long, and the operator hears about it. */
    @Builder.Default
    @Column(nullable = false)
    private int unassignedAlertMinutes = 30;

    /** A start-of-day message to each technician listing what they are carrying. */
    @Builder.Default
    @Column(nullable = false)
    private boolean dailySummaryEnabled = false;

    @Builder.Default
    @Column(nullable = false)
    private int dailySummaryHour = 7;

    /** The last day summaries went out, so a restart cannot double-send. */
    private LocalDate lastSummarySent;

    /**
     * When a technician closes a job, tell the customer it is done — in the
     * technician's own words where they gave any.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean notifyCustomerOnClose = true;
}
