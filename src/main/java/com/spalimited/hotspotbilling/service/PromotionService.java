package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Promotion;
import com.spalimited.hotspotbilling.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PromotionService {

    private final PromotionRepository promotions;

    /** The promotion running right now, if any. */
    @Transactional(readOnly = true)
    public Optional<Promotion> current() {
        Instant now = Instant.now();
        return promotions.findFirstByStartsAtBeforeAndEndsAtAfterOrderByCreatedAtDesc(now, now);
    }

    /** Applies the running promotion (if any) to a price, whole KES, min 1. */
    @Transactional(readOnly = true)
    public BigDecimal apply(BigDecimal price) {
        return current()
                .map(p -> price.multiply(BigDecimal.valueOf(100 - p.getDiscountPercent()))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                        .max(BigDecimal.ONE))
                .orElse(price);
    }

    @Transactional
    public Promotion create(String title, int discountPercent, Instant startsAt, Instant endsAt) {
        if (discountPercent < 1 || discountPercent > 90) {
            throw new IllegalArgumentException("Discount must be between 1 and 90 percent");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("The offer must end after it starts");
        }
        return promotions.save(Promotion.builder()
                .title(title)
                .discountPercent(discountPercent)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .build());
    }

    /** Ends a promotion immediately; prices revert on the next request. */
    @Transactional
    public Promotion end(Long id) {
        Promotion promo = promotions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown promotion: " + id));
        promo.setEndsAt(Instant.now());
        return promotions.save(promo);
    }
}
