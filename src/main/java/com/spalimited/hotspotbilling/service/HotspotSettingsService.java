package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.HotspotSettings;
import com.spalimited.hotspotbilling.repository.HotspotSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HotspotSettingsService {

    private final HotspotSettingsRepository repo;

    @Transactional
    public HotspotSettings get() {
        return repo.findById(HotspotSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(HotspotSettings.builder()
                        .id(HotspotSettings.SINGLETON_ID)
                        .unusedVoucherExpiryDays(0)
                        .build()));
    }

    @Transactional
    public HotspotSettings update(HotspotSettings in) {
        HotspotSettings s = get();
        String redirect = in.getPostPurchaseRedirect();
        s.setPostPurchaseRedirect(redirect == null || redirect.isBlank() ? null : redirect.trim());
        s.setUnusedVoucherExpiryDays(Math.max(0, Math.min(3650, in.getUnusedVoucherExpiryDays())));
        return repo.save(s);
    }

    @Transactional(readOnly = true)
    public String postPurchaseRedirect() {
        return get().getPostPurchaseRedirect();
    }

    @Transactional(readOnly = true)
    public int unusedVoucherExpiryDays() {
        return get().getUnusedVoucherExpiryDays();
    }
}
