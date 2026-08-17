package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.OfferNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OfferNoticeRepository extends JpaRepository<OfferNotice, Long> {

    List<OfferNotice> findByKindAndSentAtAfter(String kind, Instant since);
}
