package com.spalimited.hotspotbilling.service.olt;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.repository.NetworkDeviceRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provisioning an ONU, against an OLT that isn't one.
 *
 * <p>The highest-consequence code in this system: a wrong command here darkens
 * streets. It is also the only integration this month with no sandbox anywhere,
 * so what these tests can prove is bounded and worth stating — that the right
 * commands are built, in the right order, with nothing left blank; that a refusal
 * in prose is read as a refusal; and that nothing is sent that was not first
 * shown.
 *
 * <p>What they cannot prove is that a Huawei MA5800 agrees with the wording. That
 * needs the box, which is why every template is editable and why the preview
 * exists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OltProvisioningServiceTest {

    @Mock private NetworkDeviceRepository devices;
    @Mock private AuditService audit;

    private OltProvisioningService service;
    private FakeOlt olt;

    @BeforeEach
    void setUp() {
        service = new OltProvisioningService(devices, audit);
        olt = new FakeOlt();
        when(devices.findById(any())).thenAnswer(i -> Optional.of(device()));
    }

    @AfterEach
    void tearDown() {
        olt.close();
    }

    private NetworkDevice device() {
        return NetworkDevice.builder()
                .id(1L).name("OLT-Westlands")
                .kind(NetworkDevice.Kind.OLT)
                .oltVendor(NetworkDevice.OltVendor.HUAWEI)
                .host("127.0.0.1").cliPort(olt.port())
                .cliUsername("admin").cliPassword("secret")
                .build();
    }

    private static OltProvisioningService.Placement placement() {
        return new OltProvisioningService.Placement(
                "HWTC12345678", "0", "1", "2", "3", "House 12", "10", "20");
    }

    // ------------------------------------------------------------- the preview

    @Test
    @DisplayName("The exact commands are shown, and nothing is sent")
    void previewSendsNothing() {
        OltProvisioningService.Plan plan = service.previewAuthorise(1L, placement());

        assertThat(plan.possible()).isTrue();
        assertThat(plan.commands()).contains("interface gpon 0/1");
        assertThat(plan.commands()).anyMatch(c -> c.contains("ont add 2 3 sn-auth HWTC12345678"));
        // The whole point: an operator sees this before anything happens.
        assertThat(olt.received()).isEmpty();
    }

    @Test
    @DisplayName("Every placeholder is filled, or the command is not built at all")
    void nothingIsSentHalfFilled() {
        // An unreplaced placeholder would go to the OLT literally -- "ont add
        // {port} 3 ..." -- which is a syntax error on a good day and something
        // else entirely on a bad one.
        assertThat(OltDialect.fill("ont add {port} {onuId}",
                java.util.Map.of("{port}", "2", "{onuId}", "3")))
                .isEqualTo("ont add 2 3");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        OltDialect.fill("ont add {port} {onuId}", java.util.Map.of("{port}", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was not sent");
    }

    @Test
    @DisplayName("A name with a space in it is not two arguments")
    void namesAreMadeSafe() {
        OltProvisioningService.Plan plan = service.previewAuthorise(1L,
                new OltProvisioningService.Placement("HWTC12345678", "0", "1", "2", "3",
                        "John Mwangi", "10", "20"));

        // A space makes it a second argument on most of these CLIs, which either
        // errors or silently truncates the name to "John".
        assertThat(plan.commands()).anyMatch(c -> c.contains("desc John-Mwangi"));
    }

    @Test
    @DisplayName("No vendor means no commands, rather than guessed ones")
    void noVendorNoCommands() {
        when(devices.findById(any())).thenAnswer(i -> {
            NetworkDevice d = device();
            d.setOltVendor(null);
            return Optional.of(d);
        });

        OltProvisioningService.Plan plan = service.previewAuthorise(1L, placement());

        assertThat(plan.possible()).isFalse();
        assertThat(plan.reason()).contains("vendor");
    }

    @Test
    @DisplayName("Deauthorising without an ONU id is refused")
    void deauthoriseNeedsAnOnuId() {
        // The one command whose blast radius is a customer going dark. Refusing a
        // half-specified one is cheap insurance.
        OltProvisioningService.Plan plan = service.previewDeauthorise(1L,
                new OltProvisioningService.Placement("HWTC12345678", "0", "1", "2", null,
                        "x", null, null));

        assertThat(plan.possible()).isFalse();
        assertThat(plan.reason()).contains("ONU id");
    }

    // ---------------------------------------------------------------- the doing

    @Test
    @DisplayName("Applying sends exactly what the preview showed")
    void applySendsThePlan() {
        OltProvisioningService.Plan plan = service.previewAuthorise(1L, placement());
        for (String command : plan.commands()) {
            olt.on(command, "");
        }
        olt.on("admin", "Password:").on("secret", "");

        OltProvisioningService.Outcome outcome = service.apply(1L, plan, "authorise", "admin");

        assertThat(outcome.ok()).isTrue();
        // Not rebuilt here -- the plan is passed through, so what is sent cannot
        // drift from what was shown.
        assertThat(olt.received()).containsAll(plan.commands());
    }

    @Test
    @DisplayName("Every command is audited before it is attempted")
    void commandsAreAuditedUpFront() {
        OltProvisioningService.Plan plan = service.previewAuthorise(1L, placement());

        service.apply(1L, plan, "authorise", "admin");

        // Before, not after. A command that hangs the session still went to the
        // OLT, and a trail written only on success is missing exactly the entries
        // somebody will be hunting for.
        verify(audit).system(org.mockito.ArgumentMatchers.eq("olt.command"),
                org.mockito.ArgumentMatchers.contains("ont add 2 3 sn-auth HWTC12345678"));
    }

    @Test
    @DisplayName("A refusal in prose stops the sequence")
    void aRefusalStopsEverything() {
        OltProvisioningService.Plan plan = service.previewAuthorise(1L, placement());
        olt.on("admin", "Password:").on("secret", "");
        olt.on("enable", "").on("config", "");
        olt.on("interface gpon 0/1", "");
        // The OLT refuses the one that matters, in words.
        String add = plan.commands().stream().filter(c -> c.startsWith("ont add")).findFirst()
                .orElseThrow();
        olt.on(add, "  Failure: The ONT already exists");
        olt.on("quit", "");

        OltProvisioningService.Outcome outcome = service.apply(1L, plan, "authorise", "admin");

        assertThat(outcome.ok()).isFalse();
        // The OLT's own words, not a message of ours that discards them.
        assertThat(outcome.detail()).contains("already exists");
        // And it stopped rather than carrying on through the rest of a config
        // sequence, which is how a box ends up half configured.
        assertThat(olt.received()).doesNotContain("quit");
    }

    @Test
    @DisplayName("Bad credentials are reported rather than ploughed through")
    void badLoginStops() {
        when(devices.findById(any())).thenAnswer(i -> {
            NetworkDevice d = device();
            d.setCliPassword("wrong");
            return Optional.of(d);
        });
        olt.on("admin", "Password:").on("wrong", "Password incorrect");
        OltProvisioningService.Plan plan = service.previewAuthorise(1L, placement());

        OltProvisioningService.Outcome outcome = service.apply(1L, plan, "authorise", "admin");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("credentials");
        assertThat(olt.received()).doesNotContain("enable");
    }

    @Test
    @DisplayName("An OLT that is not there is a message, not an exception")
    void anUnreachableOltIsReported() {
        when(devices.findById(any())).thenAnswer(i -> {
            NetworkDevice d = device();
            d.setHost("127.0.0.1");
            d.setCliPort(1);   // nothing is listening here
            return Optional.of(d);
        });
        OltProvisioningService.Plan plan = service.previewAuthorise(1L, placement());

        OltProvisioningService.Outcome outcome = service.apply(1L, plan, "authorise", "admin");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.detail()).contains("Could not reach");
    }

    // ------------------------------------------------------------ the discovery

    @Test
    @DisplayName("Unregistered ONUs are found in whatever layout the OLT printed")
    void unregisteredOnusAreParsed() {
        String huawei = """
                  ----------------------------------------------------------------
                  Number    : 1
                  F/S/P     : 0/1/2
                  Ont SN    : HWTC12345678 (Huawei)
                  ----------------------------------------------------------------
                  Number    : 2
                  F/S/P     : 0/1/3
                  Ont SN    : HWTCAABBCCDD (Huawei)""";

        List<OltProvisioningService.Unregistered> found =
                OltProvisioningService.parseUnregistered(huawei);

        assertThat(found).extracting(OltProvisioningService.Unregistered::serial)
                .containsExactly("HWTC12345678", "HWTCAABBCCDD");
    }

    @Test
    @DisplayName("A one-line-per-ONU layout is read too, with its port")
    void aTabularLayoutIsAlsoRead() {
        String zte = """
                OnuIndex          Sn              State
                gpon-onu_1/2/3:1  ZTEGC1234567    unknown
                gpon-onu_1/2/4:1  ZTEGD7654321    unknown""";

        List<OltProvisioningService.Unregistered> found =
                OltProvisioningService.parseUnregistered(zte);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).serial()).isEqualTo("ZTEGC1234567");
        assertThat(found.get(0).frame()).isEqualTo("1");
        assertThat(found.get(0).slot()).isEqualTo("2");
        assertThat(found.get(0).port()).isEqualTo("3");
    }

    @Test
    @DisplayName("An EPON MAC counts as a serial")
    void eponMacsAreFound() {
        List<OltProvisioningService.Unregistered> found =
                OltProvisioningService.parseUnregistered("  1  00:1a:2b:3c:4d:5e  unregistered");

        assertThat(found).extracting(OltProvisioningService.Unregistered::serial)
                .containsExactly("00:1a:2b:3c:4d:5e");
    }

    @Test
    @DisplayName("A serial with no port is kept rather than dropped")
    void aSerialWithoutAPortIsStillReturned() {
        // An ONU silently dropped because its line was laid out unexpectedly is an
        // installer standing in somebody's garden on the phone. An operator can
        // pick the port from a dropdown.
        List<OltProvisioningService.Unregistered> found =
                OltProvisioningService.parseUnregistered("Ont SN : HWTC99998888");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).serial()).isEqualTo("HWTC99998888");
        assertThat(found.get(0).port()).isNull();
    }

    // -------------------------------------------------------------- the reading

    @Test
    @DisplayName("The words these CLIs use for no are recognised")
    void refusalsAreRecognised() {
        // No exit codes anywhere. A refusal read as success is a customer told
        // they are connected when the OLT never authorised them.
        assertThat(OltProvisioningService.errorIn("  Failure: parameter error")).isNotNull();
        assertThat(OltProvisioningService.errorIn("% Unknown command")).isNotNull();
        assertThat(OltProvisioningService.errorIn("% Invalid input detected")).isNotNull();
        assertThat(OltProvisioningService.errorIn("Error: the ONT does not exist")).isNotNull();
        assertThat(OltProvisioningService.errorIn("  Command is not supported")).isNotNull();
        // And a clean answer is not mistaken for one.
        assertThat(OltProvisioningService.errorIn("  ONT add successfully")).isNull();
        assertThat(OltProvisioningService.errorIn("")).isNull();
        assertThat(OltProvisioningService.errorIn(null)).isNull();
    }

    @Test
    @DisplayName("A pager does not truncate the answer")
    void pagedOutputIsReadWhole() throws Exception {
        // Not answering the pager leaves half a table, which reads as "this PON
        // port has four ONUs" when it has forty.
        olt.on("display ont autofind all", "Ont SN : HWTC11112222").pages("display ont autofind all");

        try (OltCli cli = new OltCli("127.0.0.1", olt.port(),
                OltDialect.forVendor(NetworkDevice.OltVendor.HUAWEI))) {
            cli.readUntilPrompt();
            String response = cli.send("display ont autofind all");

            assertThat(response).contains("HWTC11112222");
            assertThat(response).contains("rest of the output");
        }
    }
}
