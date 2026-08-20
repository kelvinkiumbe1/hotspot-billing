package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PhoneVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    /** The live challenge for a number, if there is one. */
    Optional<PhoneVerification> findByPhoneNumberAndPurposeAndVerifiedAtIsNull(
            String phoneNumber, String purpose);

    /** Whether this number has been proved recently, for any purpose. */
    List<PhoneVerification> findByPhoneNumberAndVerifiedAtIsNotNull(String phoneNumber);

    /** How many codes this number has asked for lately -- the rate limit. */
    long countByPhoneNumberAndCreatedAtAfter(String phoneNumber, Instant since);

    /** And from one address, which is the one that catches a script. */
    long countByRequestedIpAndCreatedAtAfter(String requestedIp, Instant since);

    /**
     * Live ownership tokens for a number and purpose, newest first.
     *
     * <p>A list rather than one row: every completed verification stays in the
     * table, so a number that has been proved several times has several rows and
     * only the newest carries an unspent token.
     */
    List<PhoneVerification>
            findByPhoneNumberAndPurposeAndAccessTokenHashIsNotNullAndAccessUsedAtIsNullOrderByIdDesc(
                    String phoneNumber, String purpose);

    long deleteByExpiresAtBeforeAndVerifiedAtIsNull(Instant cutoff);
}
