package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.repository.OperatorAlertSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class OperatorAlertSettingsService {

    private final OperatorAlertSettingsRepository repo;

    @Transactional
    public OperatorAlertSettings get() {
        return repo.findById(OperatorAlertSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(OperatorAlertSettings.builder()
                        .id(OperatorAlertSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public OperatorAlertSettings update(OperatorAlertSettings in) {
        OperatorAlertSettings s = get();
        s.setRouterOfflineAlert(in.isRouterOfflineAlert());
        s.setOutageCompensationEnabled(in.isOutageCompensationEnabled());
        s.setMinOutageMinutes(Math.max(0, Math.min(1440, in.getMinOutageMinutes())));
        s.setSalesDigestEnabled(in.isSalesDigestEnabled());
        s.setSalesDigestHour(Math.max(0, Math.min(23, in.getSalesDigestHour())));
        s.setCustomerOutageNotice(in.isCustomerOutageNotice());
        s.setOutageNotifyAfterMinutes(Math.max(1, Math.min(240, in.getOutageNotifyAfterMinutes())));
        s.setOutageEtaMinutes(Math.max(5, Math.min(1440, in.getOutageEtaMinutes())));
        s.setStatusPageEnabled(in.isStatusPageEnabled());
        return repo.save(s);
    }

    /** Records that today's digest has been sent, so it won't repeat. */
    @Transactional
    public void markDigestSent(LocalDate day) {
        OperatorAlertSettings s = get();
        s.setLastDigestSent(day);
        repo.save(s);
    }
}
