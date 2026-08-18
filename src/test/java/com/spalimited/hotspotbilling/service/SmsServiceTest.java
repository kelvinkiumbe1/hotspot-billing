package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.OutboundMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a phone number typed by a human survives the trip to a gateway that
 * accepts exactly one format and discards everything else without a word.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsServiceTest {

    @Mock private WhatsappService whatsappService;
    @Mock private OutboxService outboxService;
    @Mock private MessagingSettingsService messagingSettings;
    @Mock private com.spalimited.hotspotbilling.service.i18n.PhoneNumbers phones;

    private SmsService service;

    @BeforeEach
    void setUp() {
        service = new SmsService(whatsappService, outboxService, messagingSettings, phones);
        // Normalising is PhoneNumbers' job now and has its own tests; here it
        // only has to behave like Kenya so these assertions keep their meaning.
        when(phones.normalise(anyString())).thenAnswer(i ->
                com.spalimited.hotspotbilling.service.i18n.PhoneNumbers.normalise(
                        i.getArgument(0),
                        com.spalimited.hotspotbilling.service.i18n.Country.KE));
        when(whatsappService.isEnabled()).thenReturn(true);
        when(whatsappService.send(anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("The way people actually write their number is accepted")
    void acceptsHowPeopleWriteNumbers() {
        assertThat(service.normalise("0757306837")).isEqualTo("254757306837");
        assertThat(service.normalise("+254 757 306 837")).isEqualTo("254757306837");
        assertThat(service.normalise("757306837")).isEqualTo("254757306837");
        assertThat(service.normalise("254757306837")).isEqualTo("254757306837");
        // Safaricom's newer 011x range, not just 07x.
        assertThat(service.normalise("0110123456")).isEqualTo("254110123456");
    }

    @Test
    @DisplayName("Something that cannot be a Kenyan mobile is refused rather than guessed at")
    void refusesWhatIsNotANumber() {
        assertThat(service.normalise("not a number")).isNull();
        assertThat(service.normalise("12345")).isNull();
        assertThat(service.normalise(null)).isNull();
    }

    @Test
    @DisplayName("A locally-typed number reaches the customer instead of vanishing")
    void sendsToALocallyTypedNumber() {
        service.trySend("0757306837", "Your code is ABC123");

        verify(whatsappService).send(eq("+254757306837"), anyString());
        verify(outboxService).record(eq(OutboundMessage.Channel.WHATSAPP), eq("254757306837"),
                any(), anyString(), eq(true), any(), any(), any());
    }

    @Test
    @DisplayName("An unusable number is recorded as a failure, not silently dropped")
    void recordsAnUnusableNumber() {
        service.trySend("ask my brother", "Your code is ABC123");

        verify(whatsappService, never()).send(anyString(), anyString());
        verify(outboxService).record(eq(OutboundMessage.Channel.SMS), eq("ask my brother"),
                any(), anyString(), eq(false),
                org.mockito.ArgumentMatchers.contains("usable phone number"), any(), any());
    }

    @Test
    @DisplayName("Emoji are stripped for SMS, where one of them triples the bill")
    void stripsEmojiFromSms() {
        String written = "🌙 KIUMBE WiFi night rate: 30% off until 06:00.\nReply to buy.";

        String forSms = SmsService.plainForSms(written);

        assertThat(forSms).isEqualTo("KIUMBE WiFi night rate: 30% off until 06:00.\nReply to buy.");
        // 160 characters per SMS in the plain encoding, 70 once any emoji
        // forces the wide one — so this is one paid message rather than two.
        assertThat(forSms.chars().allMatch(c -> c < 128)).isTrue();
    }

    @Test
    @DisplayName("Stripping never leaves ragged spacing behind")
    void tidiesUpAfterStripping() {
        assertThat(SmsService.plainForSms("✅  Done  —  all good"))
                .isEqualTo("Done all good");
        assertThat(SmsService.plainForSms("⏰ Job #42 has had no update.\n⚠️ Please reply."))
                .isEqualTo("Job #42 has had no update.\nPlease reply.");
    }

    @Test
    @DisplayName("WhatsApp keeps the emoji, because it costs the same either way")
    void whatsappKeepsTheMessageAsWritten() {
        String written = "🎟️ Your code is ABC123";

        service.trySend("254757306837", written);

        verify(whatsappService).send(eq("+254757306837"), eq(written));
    }

    @Test
    @DisplayName("A number that could not be sent to never looks like it was")
    void neverReportsSuccessForABadNumber() {
        service.trySend("07", "Your code is ABC123");

        verify(outboxService).record(any(), anyString(), any(), anyString(),
                eq(false), anyString(), any(), any());
        verify(outboxService, never()).record(any(), anyString(), any(), anyString(),
                eq(true), any(), any(), any());
        verify(whatsappService, never()).send(anyString(), anyString());
        // And it is rejected before any gateway is even consulted.
        verify(messagingSettings, never()).sms();
    }
}
