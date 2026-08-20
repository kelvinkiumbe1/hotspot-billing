package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.domain.OntReading;
import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.repository.NetworkDeviceRepository;
import com.spalimited.hotspotbilling.repository.OntReadingRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import com.spalimited.hotspotbilling.service.OperatorAlertSettingsService;
import com.spalimited.hotspotbilling.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the sweep does with what the OLT said.
 *
 * <p>The OIDs cannot be tested without hardware. Everything after them can, and
 * this is the part that decides whether an operator gets a useful message or a
 * stream of noise they learn to ignore.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntOpticalServiceTest {

    @Mock private NetworkDeviceRepository devices;
    @Mock private OntReadingRepository readings;
    @Mock private SnmpClient snmp;
    @Mock private AuditService audit;
    @Mock private SmsService sms;
    @Mock private MessagingSettingsService messaging;
    @Mock private OperatorAlertSettingsService alertSettings;

    private OntOpticalService service;
    private Map<String, OntReading> stored;

    @BeforeEach
    void setUp() {
        service = new OntOpticalService(devices, readings, snmp, audit, sms,
                messaging, alertSettings);
        stored = new HashMap<>();

        when(alertSettings.get()).thenReturn(
                OperatorAlertSettings.builder().routerOfflineAlert(true).build());
        when(messaging.alertPhone()).thenReturn("254712345678");
        when(readings.findByOltDeviceIdAndSerial(any(), any()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(1))));
        when(readings.save(any())).thenAnswer(i -> {
            OntReading r = i.getArgument(0);
            stored.put(r.getSerial(), r);
            return r;
        });
    }

    private NetworkDevice olt() {
        return NetworkDevice.builder()
                .id(7L).name("OLT-Westlands").host("10.0.0.5")
                .kind(NetworkDevice.Kind.OLT)
                .oltVendor(NetworkDevice.OltVendor.HUAWEI)
                .enabled(true)
                .build();
    }

    private void oltReports(SnmpClient.Onu... onus) throws Exception {
        when(snmp.onus(any(), any())).thenReturn(List.of(onus));
    }

    private static SnmpClient.Onu onu(String serial, Double rx) {
        return new SnmpClient.Onu("4194304000.1", serial, "House 12", "online", rx, -2.1);
    }

    // ------------------------------------------------------------ the basics

    @Test
    @DisplayName("A healthy ONU is recorded and nobody is woken")
    void healthyOnuIsQuiet() throws Exception {
        oltReports(onu("48575443A1B2C3D4", -21.0));

        OntOpticalService.PollResult result = service.poll(olt());

        assertThat(result.polled()).isTrue();
        assertThat(result.onusSeen()).isEqualTo(1);
        assertThat(result.alerted()).isZero();
        assertThat(stored.get("48575443A1B2C3D4").getHealth())
                .isEqualTo(OpticalPower.Health.GOOD);
        verify(sms, never()).trySend(any(), any());
    }

    @Test
    @DisplayName("An ONU is recognised by its serial across polls, not by its row")
    void serialIsTheIdentity() throws Exception {
        oltReports(onu("SERIAL-A", -20.0));
        service.poll(olt());
        // Same ONU, different SNMP row -- somebody added an ONU below it, or the
        // OLT renumbered. Keying on the index would create a second record and
        // lose the history that makes a drop detectable.
        when(snmp.onus(any(), any())).thenReturn(List.of(
                new SnmpClient.Onu("4194304000.9", "SERIAL-A", "House 12", "online", -24.0, -2.1)));
        service.poll(olt());

        assertThat(stored).hasSize(1);
        assertThat(stored.get("SERIAL-A").getPreviousRxDbm()).isEqualTo(-20.0);
        assertThat(stored.get("SERIAL-A").getRxDbm()).isEqualTo(-24.0);
        assertThat(stored.get("SERIAL-A").getTableIndex()).isEqualTo(9);
    }

    // --------------------------------------------------------------- alerting

    @Test
    @DisplayName("A three-decibel drop tells somebody")
    void aDropAlerts() throws Exception {
        oltReports(onu("SERIAL-B", -20.0));
        service.poll(olt());
        oltReports(onu("SERIAL-B", -24.0));

        OntOpticalService.PollResult result = service.poll(olt());

        assertThat(result.alerted()).isEqualTo(1);
        verify(sms).trySend(any(), any());
    }

    @Test
    @DisplayName("A first reading never alerts, however bad it is")
    void theFirstSightingIsNotAnAlert() throws Exception {
        // Adding an OLT with a thousand ONUs on it must not send a thousand
        // messages -- but a link genuinely past the budget still has to be
        // reported, so this checks a merely marginal one stays quiet.
        oltReports(onu("SERIAL-C", -26.0));

        OntOpticalService.PollResult result = service.poll(olt());

        assertThat(stored.get("SERIAL-C").getHealth()).isEqualTo(OpticalPower.Health.MARGINAL);
        assertThat(result.alerted()).isZero();
    }

    @Test
    @DisplayName("A link past the budget is reported even though it never changed")
    void aLinkThatWasAlwaysBadIsStillReported() throws Exception {
        // A drop installed badly a year ago never "changes". Relying on the drop
        // alone would mean it is never mentioned.
        oltReports(onu("SERIAL-D", -29.0));

        OntOpticalService.PollResult result = service.poll(olt());

        assertThat(stored.get("SERIAL-D").getHealth()).isEqualTo(OpticalPower.Health.BAD);
        assertThat(result.alerted()).isEqualTo(1);
    }

    @Test
    @DisplayName("The same broken fibre is not reported every sweep")
    void theCooldownHolds() throws Exception {
        oltReports(onu("SERIAL-E", -31.0));
        assertThat(service.poll(olt()).alerted()).isEqualTo(1);

        // Fifteen minutes later, still broken. Repeating it every sweep is how an
        // operator learns to mute these, after which the next one is missed too.
        assertThat(service.poll(olt()).alerted()).isZero();
        assertThat(service.poll(olt()).alerted()).isZero();
    }

    @Test
    @DisplayName("After the cooldown it is mentioned again")
    void theCooldownExpires() throws Exception {
        oltReports(onu("SERIAL-F", -31.0));
        service.poll(olt());
        // Backdated past the cooldown, which is what the passage of time does.
        stored.get("SERIAL-F").setLastAlertedAt(Instant.now().minusSeconds(7 * 3600));

        assertThat(service.poll(olt()).alerted()).isEqualTo(1);
    }

    @Test
    @DisplayName("An operator who muted network alerts is not texted")
    void mutedMeansMuted() throws Exception {
        when(alertSettings.get()).thenReturn(
                OperatorAlertSettings.builder().routerOfflineAlert(false).build());
        oltReports(onu("SERIAL-G", -31.0));

        service.poll(olt());

        // Still recorded and still audited -- the worklist is the point, the text
        // message is a convenience.
        verify(sms, never()).trySend(any(), any());
        verify(audit).system(any(), any());
    }

    // --------------------------------------------------------- missing readings

    @Test
    @DisplayName("An OLT that stops reporting power does not erase the last reading")
    void aMissingReadingDoesNotWipeWhatWeKnew() throws Exception {
        oltReports(onu("SERIAL-H", -22.0));
        service.poll(olt());
        // Same ONU, no power this time. Overwriting with null would lose the
        // number somebody was looking at and make the row read as unknown.
        oltReports(onu("SERIAL-H", null));

        service.poll(olt());

        assertThat(stored.get("SERIAL-H").getRxDbm()).isEqualTo(-22.0);
    }

    @Test
    @DisplayName("An ONU with no serial is skipped rather than stored under its row")
    void anOnuWithoutASerialIsSkipped() throws Exception {
        // Storing it against the table index would attribute it to whoever
        // occupies that row next.
        when(snmp.onus(any(), any())).thenReturn(List.of(
                new SnmpClient.Onu("4194304000.1", null, null, "online", -20.0, null)));

        // SnmpClient drops these too, but the guarantee has to hold here: serial
        // is the row's identity and the column is NOT NULL, so trusting the
        // caller turns a clean skip into a failed insert that takes the rest of
        // the sweep with it.
        assertThat(service.poll(olt()).onusSeen()).isZero();
        assertThat(stored).isEmpty();
    }

    // ------------------------------------------------------------- the refusals

    @Test
    @DisplayName("An OLT with no vendor and no OIDs is not polled at all")
    void nothingToWalkIsSaidPlainly() {
        NetworkDevice bare = olt();
        bare.setOltVendor(null);

        OntOpticalService.PollResult result = service.poll(bare);

        // Walking a made-up OID reports an OLT with no ONUs, which looks like a
        // working integration finding nothing.
        assertThat(result.polled()).isFalse();
        assertThat(result.error()).contains("vendor");
    }

    @Test
    @DisplayName("A switch is not an OLT")
    void onlyOltsAreWalked() {
        NetworkDevice aSwitch = olt();
        aSwitch.setKind(NetworkDevice.Kind.SWITCH);

        assertThat(service.poll(aSwitch).polled()).isFalse();
    }

    @Test
    @DisplayName("An OLT that answers with nothing says which of the two it might be")
    void anEmptyTableNamesBothPossibilities() throws Exception {
        oltReports();

        OntOpticalService.PollResult result = service.poll(olt());

        // A wrong OID and an OLT with no ONUs are indistinguishable over SNMP, so
        // the message must not claim to know which.
        assertThat(result.polled()).isTrue();
        assertThat(result.error()).contains("OIDs");
    }

    // -------------------------------------------------------------- the ordering

    @Test
    @DisplayName("The worklist is worst first, by band rather than by number")
    void attentionIsOrderedBySeverity() {
        when(readings.findByHealthIn(any())).thenReturn(List.of(
                reading("marginal", -26.0, OpticalPower.Health.MARGINAL),
                reading("hot", -4.0, OpticalPower.Health.TOO_HOT),
                reading("dark", -33.0, OpticalPower.Health.DOWN),
                reading("bad", -28.0, OpticalPower.Health.BAD)));

        List<OntReading> list = service.attentionNeeded();

        // A -4 dBm receiver being overloaded is a real fault and would sort last
        // on the number alone. The band is what decides.
        assertThat(list).extracting(OntReading::getSerial)
                .containsExactly("dark", "bad", "hot", "marginal");
    }

    private static OntReading reading(String serial, Double rx, OpticalPower.Health health) {
        return OntReading.builder().serial(serial).rxDbm(rx).health(health).build();
    }
}
