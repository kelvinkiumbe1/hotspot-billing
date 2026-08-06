package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    Optional<SubscriptionPayment> findByCheckoutRequestId(String checkoutRequestId);

    List<SubscriptionPayment> findBySubscriberIdOrderByCreatedAtDesc(Long subscriberId);

    void deleteBySubscriberId(Long subscriberId);
}
