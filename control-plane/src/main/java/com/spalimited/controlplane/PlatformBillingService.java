package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Creates and settles platform-fee invoices, collecting via {@link PlatformStk}. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformBillingService {

    private final PlatformInvoiceRepository invoices;
    private final PlatformStk stk;

    /**
     * Start collecting a period's fee. If it's already paid, hand that back
     * untouched; otherwise raise a fresh invoice and push the STK.
     */
    @Transactional
    public PlatformInvoice charge(String slug, String period, BigDecimal amount, String phone) {
        return invoices.findFirstByTenantSlugAndPeriodAndStatus(slug, period, PlatformInvoice.Status.PAID)
                .orElseGet(() -> {
                    PlatformInvoice inv = invoices.save(PlatformInvoice.builder()
                            .tenantSlug(slug)
                            .period(period)
                            .amount(amount)
                            .phone(phone)
                            .status(PlatformInvoice.Status.PENDING)
                            .build());
                    PlatformStk.StkResult res = stk.push(inv);
                    inv.setCheckoutId(res.checkoutId());
                    inv.setDetail(res.detail());
                    if (!res.initiated()) {
                        inv.setStatus(PlatformInvoice.Status.FAILED);
                    }
                    return invoices.save(inv);
                });
    }

    @Transactional
    public void markPaid(String checkoutId, String receipt) {
        invoices.findByCheckoutId(checkoutId).ifPresent(inv -> {
            inv.setStatus(PlatformInvoice.Status.PAID);
            inv.setMpesaReceipt(receipt);
            inv.setPaidAt(Instant.now());
            inv.setDetail("Paid.");
            invoices.save(inv);
            log.info("Platform invoice {} ({} {}) paid — receipt {}", inv.getId(),
                    inv.getTenantSlug(), inv.getPeriod(), receipt);
        });
    }

    @Transactional
    public void markFailed(String checkoutId, String reason) {
        invoices.findByCheckoutId(checkoutId).ifPresent(inv -> {
            inv.setStatus(PlatformInvoice.Status.FAILED);
            inv.setDetail(reason);
            invoices.save(inv);
        });
    }

    /** Dry-run/dev: mark a pending invoice paid without real M-Pesa. */
    @Transactional
    public PlatformInvoice confirmManually(Long id) {
        PlatformInvoice inv = invoices.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such invoice"));
        inv.setStatus(PlatformInvoice.Status.PAID);
        inv.setMpesaReceipt("MANUAL-" + id);
        inv.setPaidAt(Instant.now());
        inv.setDetail("Marked paid manually.");
        return invoices.save(inv);
    }

    @Transactional(readOnly = true)
    public PlatformInvoice latestForPeriod(String slug, String period) {
        return invoices.findByTenantSlugOrderByCreatedAtDesc(slug).stream()
                .filter(i -> period.equals(i.getPeriod()))
                .findFirst()
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PlatformInvoice> forTenant(String slug) {
        return invoices.findByTenantSlugOrderByCreatedAtDesc(slug);
    }
}
