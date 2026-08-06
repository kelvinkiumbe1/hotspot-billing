package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Promotion;
import com.spalimited.hotspotbilling.repository.PromotionRepository;
import com.spalimited.hotspotbilling.service.PromotionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Limited-time offers: public banner info + admin management. */
@RestController
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;
    private final PromotionRepository promotions;

    /** What the portal needs to draw the offer banner and discounted prices. */
    @GetMapping("/api/promotion")
    public Map<String, Object> current() {
        Map<String, Object> out = new HashMap<>();
        promotionService.current().ifPresentOrElse(p -> {
            out.put("active", true);
            out.put("title", p.getTitle());
            out.put("discountPercent", p.getDiscountPercent());
            out.put("endsAt", p.getEndsAt());
        }, () -> out.put("active", false));
        return out;
    }

    // --- Admin ---

    public record PromotionRequest(
            @NotBlank String title,
            @Min(1) @Max(90) int discountPercent,
            Instant startsAt,
            @NotNull Instant endsAt) {
    }

    @GetMapping("/api/admin/promotions")
    public List<Promotion> all() {
        return promotions.findTop20ByOrderByCreatedAtDesc();
    }

    @PostMapping("/api/admin/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    public Promotion create(@Valid @RequestBody PromotionRequest request) {
        Instant startsAt = request.startsAt() != null ? request.startsAt() : Instant.now();
        return promotionService.create(request.title(), request.discountPercent(), startsAt, request.endsAt());
    }

    @PatchMapping("/api/admin/promotions/{id}/end")
    public Promotion end(@PathVariable Long id) {
        return promotionService.end(id);
    }
}
