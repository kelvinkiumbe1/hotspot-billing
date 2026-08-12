package com.spalimited.controlplane;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local development: logs the STK it would send and leaves the invoice PENDING.
 * A developer marks it paid with the confirm endpoint to walk the whole flow
 * without real M-Pesa. Default when zidi.platform.provider isn't DARAJA.
 */
@Component
@ConditionalOnProperty(name = "zidi.platform.provider", havingValue = "DRY_RUN", matchIfMissing = true)
@Slf4j
public class DryRunPlatformStk implements PlatformStk {

    @Override
    public StkResult push(PlatformInvoice invoice) {
        log.info("[DRY RUN] Would push M-Pesa STK of KES {} to {} for {} ({}). "
                        + "Confirm with POST /api/platform/invoice/{}/confirm to mark it paid.",
                invoice.getAmount(), invoice.getPhone(), invoice.getTenantSlug(),
                invoice.getPeriod(), invoice.getId());
        return StkResult.ok("DRYRUN-" + invoice.getId(), "Dry run — awaiting manual confirmation.");
    }
}
