package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.RadiusSettings;
import com.spalimited.hotspotbilling.repository.RadiusSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The one settings row, created on first read if the migration's insert is gone. */
@Service
@RequiredArgsConstructor
public class RadiusSettingsService {

    private final RadiusSettingsRepository repository;

    @Transactional
    public RadiusSettings get() {
        return repository.findById(1L)
                .orElseGet(() -> repository.save(RadiusSettings.builder().id(1L).build()));
    }

    @Transactional
    public RadiusSettings save(RadiusSettings incoming, String updatedBy) {
        RadiusSettings settings = get();
        settings.setEnabled(incoming.isEnabled());
        settings.setAuthPort(sane(incoming.getAuthPort(), 1812));
        settings.setAcctPort(sane(incoming.getAcctPort(), 1813));
        // Below sixty seconds a busy site spends more time reporting than
        // serving; above an hour, a router that dies loses an hour of billing.
        settings.setInterimSeconds(Math.min(3_600, Math.max(60, incoming.getInterimSeconds())));
        settings.setDisconnectEnabled(incoming.isDisconnectEnabled());
        settings.setUpdatedBy(updatedBy);
        return repository.save(settings);
    }

    private static int sane(int port, int fallback) {
        return port > 0 && port <= 65_535 ? port : fallback;
    }
}
