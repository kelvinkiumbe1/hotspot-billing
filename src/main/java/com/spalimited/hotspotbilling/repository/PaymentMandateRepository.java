package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PaymentMandate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMandateRepository extends JpaRepository<PaymentMandate, Long> {

    Optional<PaymentMandate> findBySubscriberId(Long subscriberId);

    Optional<PaymentMandate> findByExternalRef(String externalRef);

    /**
     * The mandate a payment reference was meant to authorise.
     *
     * <p>How a token finds its mandate. Matching on the reference the customer
     * consented against, rather than on the subscriber, is what stops a later
     * unrelated payment silently storing a card nobody agreed to reuse.
     */
    Optional<PaymentMandate> findByConsentReference(String consentReference);

    List<PaymentMandate> findByStatus(PaymentMandate.Status status);

    long countByStatus(PaymentMandate.Status status);
}
