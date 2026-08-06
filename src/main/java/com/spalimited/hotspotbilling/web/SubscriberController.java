package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.service.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Monthly PPPoE subscribers (HTTP Basic, ADMIN role). */
@RestController
@RequestMapping("/api/admin/subscribers")
@RequiredArgsConstructor
public class SubscriberController {

    private final SubscriberRepository subscribers;
    private final SubscriptionPaymentRepository payments;
    private final SubscriptionService subscriptionService;

    @GetMapping
    public List<Subscriber> all() {
        return subscribers.findAllByOrderByCreatedAtAsc();
    }

    public record CreateRequest(
            @NotBlank String fullName,
            @Pattern(regexp = "254\\d{9}", message = "Phone must be in 2547XXXXXXXX format") String phoneNumber,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._@-]{3,40}",
                    message = "PPPoE username must be 3-40 letters, digits, dots, dashes, @ or underscores")
            String pppoeUsername,
            @NotBlank @Size(min = 6) String pppoePassword,
            String bandwidth,
            @NotNull @Min(1) BigDecimal monthlyFee,
            @Min(0) @Max(12) Integer initialMonths,
            String initialMethod) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Subscriber create(@Valid @RequestBody CreateRequest request) {
        return subscriptionService.create(
                request.fullName(),
                request.phoneNumber(),
                request.pppoeUsername(),
                request.pppoePassword(),
                request.bandwidth(),
                request.monthlyFee(),
                request.initialMonths() != null ? request.initialMonths() : 1,
                "MPESA".equalsIgnoreCase(request.initialMethod())
                        ? SubscriptionPayment.Method.MPESA : SubscriptionPayment.Method.CASH);
    }

    public record MonthsRequest(@Min(1) @Max(12) int months) {
    }

    /** Records a cash/off-system payment and extends the subscription. */
    @PostMapping("/{id}/payments")
    public SubscriptionPayment recordPayment(@PathVariable Long id, @Valid @RequestBody MonthsRequest request) {
        return subscriptionService.recordCashPayment(id, request.months());
    }

    /** Sends an M-Pesa STK prompt to the subscriber's phone. */
    @PostMapping("/{id}/stk")
    public Map<String, Object> stk(@PathVariable Long id, @Valid @RequestBody MonthsRequest request) {
        SubscriptionPayment payment = subscriptionService.initiateStk(id, request.months());
        return Map.of(
                "paymentId", payment.getId(),
                "amount", payment.getAmount(),
                "message", "STK prompt sent — the subscriber should enter their M-Pesa PIN");
    }

    @GetMapping("/{id}/payments")
    public List<SubscriptionPayment> paymentHistory(@PathVariable Long id) {
        return payments.findBySubscriberIdOrderByCreatedAtDesc(id);
    }

    @PatchMapping("/{id}/suspend")
    public Subscriber suspend(@PathVariable Long id) {
        return subscriptionService.suspend(id);
    }

    @PatchMapping("/{id}/activate")
    public Subscriber activate(@PathVariable Long id) {
        return subscriptionService.activate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        subscriptionService.delete(id);
    }
}
