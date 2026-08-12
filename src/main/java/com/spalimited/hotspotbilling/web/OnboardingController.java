package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.MessagingSettings;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Drives the "Set up your account" checklist a new ISP sees on the dashboard.
 * Each step's done-state is computed from real data, so it ticks off as the
 * operator actually configures things and the card retires once everything's
 * set. Read-only, no persistence of its own.
 */
@RestController
@RequestMapping("/api/admin/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final PortalSettingsService portalSettings;
    private final PaymentGatewayService paymentGateways;
    private final MessagingSettingsService messaging;
    private final PlanRepository plans;
    private final SubscriberRepository subscribers;
    private final RouterRepository routers;

    /** One checklist item. `tab` is the admin section to open when clicked. */
    public record Step(String key, String label, String description, boolean done, String tab) {
    }

    public record Onboarding(List<Step> steps, int completed, int total, boolean allDone) {
    }

    @GetMapping
    public Onboarding get() {
        PortalSettings portal = portalSettings.settings();
        boolean branded = (portal.getBusinessName() != null && !portal.getBusinessName().isBlank())
                || (portal.getLogoFilename() != null && !portal.getLogoFilename().isBlank());

        boolean paymentReady = paymentGateways.active()
                .map(PaymentGateway::isConfigured).orElse(false);

        MessagingSettings ms = messaging.settings();
        boolean smsReady = ms.isSmsEnabled()
                && ms.getSmsApiKey() != null && !ms.getSmsApiKey().isBlank();

        List<Step> steps = List.of(
                new Step("branding", "Set your network name & logo",
                        "Make the customer portal yours.", branded, "promos"),
                new Step("payment", "Connect a payment gateway",
                        "M-Pesa STK or a paybill/till so customers can pay.", paymentReady, "paybill"),
                new Step("sms", "Configure an SMS provider",
                        "Send vouchers and receipts to customers.", smsReady, "settings"),
                new Step("plans", "Create your first plan",
                        "The packages customers buy.", plans.count() > 0, "plans"),
                new Step("subscribers", "Add or import your first subscriber",
                        "Bring your PPPoE customers over.", subscribers.count() > 0, "subscribers"),
                new Step("router", "Add your router",
                        "Connect a MikroTik, or skip if you're hotspot-only.", routers.count() > 0, "routers")
        );

        int completed = (int) steps.stream().filter(Step::done).count();
        return new Onboarding(steps, completed, steps.size(), completed == steps.size());
    }
}
