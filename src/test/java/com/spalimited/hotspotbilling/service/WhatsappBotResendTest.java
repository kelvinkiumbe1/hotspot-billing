package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Getting your own code back out of the chat.
 *
 * <p>Buying a pass and being sent the code is the customer acting now. Asking
 * for an old one is retrieval of history, and history is what somebody who has
 * taken over a WhatsApp account goes looking for — the account is reachable
 * without the SIM, so the SIM is where the code should land.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WhatsappBotResendTest {

    private static final String PHONE = "254712345678";

    @Mock private com.spalimited.hotspotbilling.repository.PlanRepository plans;
    @Mock private PaymentService payments;
    @Mock private com.spalimited.hotspotbilling.repository.SubscriberRepository subscribers;
    @Mock private SubscriptionService subscriptions;
    @Mock private com.spalimited.hotspotbilling.repository.SupportTicketRepository tickets;
    @Mock private VoucherRepository vouchers;
    @Mock private PortalSettingsService portalSettings;
    @Mock private ReferralService referralService;
    @Mock private VoucherService voucherService;
    @Mock private SmsService smsService;
    @Mock private CustomPlanService customPlanService;
    @Mock private FieldOpsService fieldOps;
    @Mock private com.spalimited.hotspotbilling.repository.LeadRepository leads;
    @Mock private MoneyService money;
    @Mock private com.spalimited.hotspotbilling.service.i18n.Messages messages;
    @Mock private com.spalimited.hotspotbilling.service.i18n.PhoneNumbers phones;

    @InjectMocks
    private WhatsappBotService bot;

    @BeforeEach
    void setUp() {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().businessName("SPA WiFi").build());
        when(phones.normalise(anyString())).thenAnswer(i -> {
            String raw = i.getArgument(0);
            return raw == null ? null : raw.replaceAll("\\D", "");
        });
        when(smsService.isEnabled()).thenReturn(true);
        when(messages.paymentBrand()).thenReturn("M-Pesa");

        Voucher v = Voucher.builder().id(1L).code("GXYE3ED6").phoneNumber(PHONE)
                .status(Voucher.Status.UNUSED).build();
        when(vouchers.findByPhoneNumberOrderByCreatedAtDesc(anyString())).thenReturn(List.of(v));
        when(voucherService.statusOf(any())).thenReturn(
                new VoucherService.PassStatus("GXYE3ED6", "1 Hour", "UNUSED",
                        120L, null, 0L, null, null));
    }

    /** Menu → 5 (resend). */
    private String resend() {
        bot.replyWithPhone(PHONE, "hi");
        return bot.replyWithPhone(PHONE, "5");
    }

    @Test
    @DisplayName("the chat shows only the tail, and the full code goes by SMS")
    void codeIsTextedNotChatted() {
        String reply = resend();

        // The whole point: a hijacked WhatsApp account reads this message and
        // still cannot use the pass.
        assertThat(reply).doesNotContain("GXYE3ED6");
        assertThat(reply).contains("ED6");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsService).trySend(anyString(), text.capture());
        assertThat(text.getValue()).contains("GXYE3ED6");
    }

    @Test
    @DisplayName("with no SMS to fall back on, the code is shown rather than withheld")
    void withoutSmsTheCodeIsStillGiven() {
        when(smsService.isEnabled()).thenReturn(false);

        String reply = resend();

        // Refusing outright would lock a customer out of a pass they paid for,
        // which is a worse outcome than the chat holding the code.
        assertThat(reply).contains("GXYE3ED6");
        verify(smsService, never()).trySend(anyString(), anyString());
    }

    @Test
    @DisplayName("a fourth ask within the hour is refused")
    void resendIsRateLimited() {
        resend();
        resend();
        resend();

        String fourth = resend();

        // The tail is a hint, and a hint you can ask for without limit is a
        // guessing game.
        assertThat(fourth).contains("few times");
        assertThat(fourth).doesNotContain("ED6");
        verify(smsService, org.mockito.Mockito.times(3)).trySend(anyString(), anyString());
    }
}
