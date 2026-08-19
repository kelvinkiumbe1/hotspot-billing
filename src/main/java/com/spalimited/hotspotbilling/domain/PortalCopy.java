package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One line of portal wording an operator has rewritten.
 *
 * <p>The portal ships a hundred and eight strings in four languages and the
 * backend forty-three more. All of them are keyed, and this table overrides them
 * one at a time: a row exists only where an operator has actually typed
 * something, and the built-in default answers everywhere else.
 *
 * <p>That absence-means-default rule is the whole safety design. An operator
 * cannot empty the portal by clearing a field, cannot break a language they do
 * not speak, and a string added in a future release appears in its own words
 * rather than as a blank space or a key name. Deleting a row restores the
 * original, which makes "put it back" a real operation rather than a support
 * ticket.
 *
 * <p>Per language on purpose. A Tanzanian operator rewriting the Swahili should
 * not disturb the English a visitor sees, and an operator who only speaks one of
 * their four languages should be able to leave the rest alone.
 */
@Entity
@Table(name = "portal_copy",
        uniqueConstraints = @UniqueConstraint(columnNames = {"language", "copy_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalCopy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Which language this wording is for: EN, SW, FR or PT.
     *
     * <p>Stored as the two-letter code rather than a foreign key to the Language
     * enum, so a language added later does not need a migration and a language
     * removed later leaves rows that are simply never read.
     */
    @Column(nullable = false, length = 8)
    private String language;

    /**
     * The string's key — {@code card.buy}, {@code pay.checkPhone}.
     *
     * <p>Named copy_key in the database because "key" is reserved in enough
     * dialects to be a nuisance.
     */
    @Column(name = "copy_key", nullable = false, length = 120)
    private String copyKey;

    /**
     * What the operator wants it to say.
     *
     * <p>Generous length because a terms line or a support message can be long,
     * and truncating an operator's own words at save time would be worse than
     * letting the portal wrap them.
     */
    @Column(nullable = false, length = 2000)
    private String text;

    private String updatedBy;

    private Instant updatedAt;

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    void stamp() {
        updatedAt = Instant.now();
    }
}
