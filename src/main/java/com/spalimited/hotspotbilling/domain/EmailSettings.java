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

/**
 * The operator's own SMTP server, held as a single row (id = 1). Same
 * reasoning as {@link MessagingSettings}: the people whose mailbox it is
 * should be able to set it up themselves, without editing a properties
 * file and restarting. Used for receipts, password resets and reports.
 */
@Entity
@Table(name = "email_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** SMTP server hostname, e.g. smtp.gmail.com. */
    private String host;

    @Builder.Default
    @Column(nullable = false)
    private int port = 587;

    private String username;

    private String password;

    /** Address the mail is sent from, e.g. billing@yourdomain.com. */
    private String fromAddress;

    /** Display name shown alongside the from address. */
    private String fromName;

    /** STARTTLS on the standard submission port (587); off for implicit SSL (465). */
    @Builder.Default
    @Column(nullable = false)
    private boolean startTls = true;

    private String updatedBy;

    @jakarta.persistence.Transient
    public boolean isConfigured() {
        return enabled
                && host != null && !host.isBlank()
                && fromAddress != null && !fromAddress.isBlank();
    }
}
