package com.spalimited.hotspotbilling.service.acs;

import com.spalimited.hotspotbilling.domain.CpeDevice;
import com.spalimited.hotspotbilling.domain.CpeParameter;
import com.spalimited.hotspotbilling.domain.CpeTask;
import com.spalimited.hotspotbilling.repository.CpeDeviceRepository;
import com.spalimited.hotspotbilling.repository.CpeParameterRepository;
import com.spalimited.hotspotbilling.repository.CpeTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A CPE, simulated.
 *
 * <p>TR-069 is the one integration this month that could be verified without the
 * hardware, because CWMP is a published specification rather than a vendor's
 * private arrangement. So the envelopes below are real ones — the shapes a device
 * actually posts — and the whole session is driven through the service exactly as
 * a router in somebody's house would drive it.
 *
 * <p>What that proves is the session state machine: that an Inform registers a
 * device, that an empty POST draws out an order, that a result closes a task, and
 * that a fault fails one instead of losing it. What it cannot prove is that a
 * particular router agrees, which is what a lab unit is for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AcsServiceTest {

    @Mock private CpeDeviceRepository devices;
    @Mock private CpeTaskRepository tasks;
    @Mock private CpeParameterRepository parameters;

    private AcsService acs;
    private final Map<Long, CpeDevice> deviceStore = new HashMap<>();
    private final Map<Long, CpeTask> taskStore = new HashMap<>();
    private final Map<String, CpeParameter> paramStore = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        acs = new AcsService(devices, tasks, parameters, new ObjectMapper());
        deviceStore.clear();
        taskStore.clear();
        paramStore.clear();

        when(devices.save(any())).thenAnswer(i -> {
            CpeDevice d = i.getArgument(0);
            if (d.getId() == null) {
                d.setId(ids.getAndIncrement());
            }
            deviceStore.put(d.getId(), d);
            return d;
        });
        when(devices.findById(any())).thenAnswer(i ->
                Optional.ofNullable(deviceStore.get(i.<Long>getArgument(0))));
        when(devices.findByOuiAndSerialNumber(any(), any())).thenAnswer(i -> deviceStore.values()
                .stream()
                .filter(d -> d.getOui().equals(i.getArgument(0))
                        && d.getSerialNumber().equals(i.getArgument(1)))
                .findFirst());

        when(tasks.save(any())).thenAnswer(i -> {
            CpeTask t = i.getArgument(0);
            if (t.getId() == null) {
                t.setId(ids.getAndIncrement());
            }
            taskStore.put(t.getId(), t);
            return t;
        });
        when(tasks.findById(any())).thenAnswer(i ->
                Optional.ofNullable(taskStore.get(i.<Long>getArgument(0))));
        when(tasks.findByCpeDeviceIdAndStatusOrderByIdAsc(any(), any())).thenAnswer(i -> {
            List<CpeTask> found = new ArrayList<>(taskStore.values().stream()
                    .filter(t -> t.getCpeDeviceId().equals(i.getArgument(0))
                            && t.getStatus() == i.getArgument(1))
                    .sorted(java.util.Comparator.comparing(CpeTask::getId))
                    .toList());
            return found;
        });

        when(parameters.save(any())).thenAnswer(i -> {
            CpeParameter p = i.getArgument(0);
            paramStore.put(p.getCpeDeviceId() + "|" + p.getName(), p);
            return p;
        });
        when(parameters.findByCpeDeviceIdAndName(any(), any())).thenAnswer(i ->
                Optional.ofNullable(paramStore.get(
                        i.<Long>getArgument(0) + "|" + i.<String>getArgument(1))));
    }

    // ------------------------------------------------------ the real envelopes

    /** A TR-098 Inform, in the shape a consumer router actually posts. */
    private static String inform098() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                 xmlns:cwmp="urn:dslforum-org:cwmp-1-0">
                <soap:Header><cwmp:ID soap:mustUnderstand="1">42</cwmp:ID></soap:Header>
                <soap:Body><cwmp:Inform>
                  <DeviceId>
                    <Manufacturer>TP-Link</Manufacturer>
                    <OUI>001122</OUI>
                    <ProductClass>EC220-G5</ProductClass>
                    <SerialNumber>SN-ABC-001</SerialNumber>
                  </DeviceId>
                  <Event><EventStruct><EventCode>2 PERIODIC</EventCode>
                    <CommandKey></CommandKey></EventStruct></Event>
                  <MaxEnvelopes>1</MaxEnvelopes>
                  <RetryCount>0</RetryCount>
                  <ParameterList>
                    <ParameterValueStruct>
                      <Name>InternetGatewayDevice.DeviceInfo.SoftwareVersion</Name>
                      <Value xsi:type="xsd:string">1.4.2</Value></ParameterValueStruct>
                    <ParameterValueStruct>
                      <Name>InternetGatewayDevice.ManagementServer.ConnectionRequestURL</Name>
                      <Value xsi:type="xsd:string">http://10.1.2.3:7547/cr</Value>
                      </ParameterValueStruct>
                  </ParameterList>
                </cwmp:Inform></soap:Body></soap:Envelope>""";
    }

    /** The same device generation later: TR-181, and a different namespace. */
    private static String inform181() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                 xmlns:cwmp="urn:dslforum-org:cwmp-1-2">
                <soap:Header><cwmp:ID soap:mustUnderstand="1">7</cwmp:ID></soap:Header>
                <soap:Body><cwmp:Inform>
                  <DeviceId><Manufacturer>Huawei</Manufacturer><OUI>00E0FC</OUI>
                    <ProductClass>HG8145</ProductClass>
                    <SerialNumber>SN-XYZ-002</SerialNumber></DeviceId>
                  <Event><EventStruct><EventCode>1 BOOT</EventCode></EventStruct></Event>
                  <ParameterList>
                    <ParameterValueStruct><Name>Device.DeviceInfo.SoftwareVersion</Name>
                      <Value>2.0.9</Value></ParameterValueStruct>
                  </ParameterList>
                </cwmp:Inform></soap:Body></soap:Envelope>""";
    }

    private AcsService.Reply post(String xml, String session) {
        return acs.handle(xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8),
                session, "10.0.0.9");
    }

    // ------------------------------------------------------------- registering

    @Test
    @DisplayName("A router introduces itself and a record appears")
    void informRegistersTheDevice() {
        AcsService.Reply reply = post(inform098(), null);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("InformResponse");
        // The ID is echoed. A CPE that gets a different one back treats the reply
        // as unrelated and the session stalls.
        assertThat(reply.body()).contains("<cwmp:ID soap:mustUnderstand=\"1\">42</cwmp:ID>");

        CpeDevice device = deviceStore.values().iterator().next();
        assertThat(device.getSerialNumber()).isEqualTo("SN-ABC-001");
        assertThat(device.getOui()).isEqualTo("001122");
        assertThat(device.getManufacturer()).isEqualTo("TP-Link");
        assertThat(device.getSoftwareVersion()).isEqualTo("1.4.2");
        assertThat(device.getConnectionRequestUrl()).isEqualTo("http://10.1.2.3:7547/cr");
        assertThat(device.getLastEvent()).contains("PERIODIC");
    }

    @Test
    @DisplayName("The data model is read off the Inform, not asked for separately")
    void dataModelIsDetectedFromTheInform() {
        post(inform098(), "s1");
        assertThat(deviceStore.values().iterator().next().getDataModel())
                .isEqualTo(CpeDevice.DataModel.TR098);

        deviceStore.clear();
        post(inform181(), "s2");
        assertThat(deviceStore.values().iterator().next().getDataModel())
                .isEqualTo(CpeDevice.DataModel.TR181);
    }

    @Test
    @DisplayName("The CWMP version the device used is the one it gets back")
    void theNamespaceIsEchoed() {
        // cwmp-1-0 through 1-4 are all in the field, and a device that sent 1-0
        // and receives 1-2 ignores the reply. It looks legal and does nothing.
        assertThat(post(inform098(), "a").body()).contains("urn:dslforum-org:cwmp-1-0");
        assertThat(post(inform181(), "b").body()).contains("urn:dslforum-org:cwmp-1-2");
    }

    @Test
    @DisplayName("The same router calling twice is one record, not two")
    void aReturningDeviceIsRecognised() {
        post(inform098(), "s1");
        post(inform098(), "s2");

        assertThat(deviceStore).hasSize(1);
    }

    @Test
    @DisplayName("An Inform with no serial is refused rather than filed under nothing")
    void anInformWithoutASerialIsDropped() {
        String noSerial = inform098().replace(
                "<SerialNumber>SN-ABC-001</SerialNumber>", "<SerialNumber></SerialNumber>");

        AcsService.Reply reply = post(noSerial, "s1");

        // Filing it against anything else would attribute one customer's box to
        // another.
        assertThat(reply.status()).isEqualTo(204);
        assertThat(deviceStore).isEmpty();
    }

    // -------------------------------------------------------------- the session

    @Test
    @DisplayName("An empty POST is the cue for an order, not a bad request")
    void theEmptyPostDrawsOutAnOrder() {
        post(inform098(), "s1");
        acs.queue(1L, CpeTask.Kind.REBOOT, Map.of(), "admin");

        // The empty body is how a CPE says "I have nothing more". Treating it as
        // malformed ends every session before anything useful happens.
        AcsService.Reply reply = post(null, "s1");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("cwmp:Reboot");
    }

    @Test
    @DisplayName("Nothing queued ends the session with a 204")
    void nothingToDoEndsTheSession() {
        post(inform098(), "s1");

        AcsService.Reply reply = post(null, "s1");

        // 204 is how a CWMP session ends. Anything else and the device keeps
        // posting.
        assertThat(reply.status()).isEqualTo(204);
        assertThat(reply.body()).isNull();
    }

    @Test
    @DisplayName("A task goes PENDING, then SENT, then DONE")
    void aTaskRunsItsCourse() {
        post(inform098(), "s1");
        CpeTask task = acs.queue(1L, CpeTask.Kind.REBOOT, Map.of(), "admin");
        assertThat(task.getStatus()).isEqualTo(CpeTask.Status.PENDING);

        post(null, "s1");
        assertThat(taskStore.get(task.getId()).getStatus()).isEqualTo(CpeTask.Status.SENT);

        post(rebootResponse(), "s1");
        assertThat(taskStore.get(task.getId()).getStatus()).isEqualTo(CpeTask.Status.DONE);
        assertThat(taskStore.get(task.getId()).getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("One order at a time, however many are queued")
    void ordersAreNotBatched() {
        post(inform098(), "s1");
        acs.queue(1L, CpeTask.Kind.REBOOT, Map.of(), "admin");
        acs.queue(1L, CpeTask.Kind.FACTORY_RESET, Map.of(), "admin");

        // CWMP allows several envelopes in flight and no CPE does it well; a batch
        // that half-applies is harder to reason about than a queue that drains.
        String first = post(null, "s1").body();
        assertThat(first).contains("cwmp:Reboot");
        assertThat(first).doesNotContain("FactoryReset");
    }

    // ------------------------------------------------------------- the faults

    @Test
    @DisplayName("A refused order fails its task with the reason, and the session goes on")
    void aFaultFailsTheTaskAndKeepsGoing() {
        post(inform098(), "s1");
        CpeTask task = acs.queue(1L, CpeTask.Kind.REBOOT, Map.of(), "admin");
        post(null, "s1");

        AcsService.Reply reply = post(fault("9005", "Invalid parameter name"), "s1");

        // "9005 Invalid parameter name" is the difference between a mystery and a
        // wrong data model, and it is exactly what an operator needs to read.
        assertThat(taskStore.get(task.getId()).getStatus()).isEqualTo(CpeTask.Status.FAILED);
        assertThat(taskStore.get(task.getId()).getFault()).contains("9005");
        assertThat(taskStore.get(task.getId()).getFault()).contains("Invalid parameter name");
        // And the session continues rather than being abandoned.
        assertThat(reply.status()).isEqualTo(204);
    }

    @Test
    @DisplayName("Rubbish from a device ends the session politely")
    void garbageDoesNotThrow() {
        // A CPE that receives an exception page does not report it anywhere a
        // human will see: it retries, fails, and is simply absent with nobody
        // knowing why.
        AcsService.Reply reply = post("this is not XML at all", "s1");

        assertThat(reply.status()).isEqualTo(204);
    }

    @Test
    @DisplayName("A hostile device cannot make the ACS read a file")
    void xxeFromACpeIsRefused() {
        // The clients here are cheap routers in other people's houses. A
        // compromised one posting this is not hypothetical.
        String hostile = """
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                <soap:Body><cwmp:Inform xmlns:cwmp="urn:dslforum-org:cwmp-1-0">
                <DeviceId><SerialNumber>&leak;</SerialNumber></DeviceId>
                </cwmp:Inform></soap:Body></soap:Envelope>""";

        AcsService.Reply reply = post(hostile, "s1");

        assertThat(reply.status()).isEqualTo(204);
        assertThat(deviceStore).isEmpty();
    }

    // ----------------------------------------------------- the parameter paths

    @Test
    @DisplayName("The WiFi password goes to the path this device's model uses")
    void wifiPasswordTakesTheRightPathPerModel() {
        post(inform098(), "s1");
        acs.queue(1L, CpeTask.Kind.SET_PARAMETERS,
                Map.of("settings", Map.of("WIFI_PASSWORD", "newsecret")), "admin");

        String order = post(null, "s1").body();

        // TR-098's path. Sending TR-181's here gets fault 9005 -- or worse, a
        // device that accepts it, reports success and changes nothing.
        assertThat(order).contains(
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.KeyPassphrase");
        assertThat(order).contains("newsecret");
        // And the ParameterKey is the task, which is how the next Inform can say
        // which change it applied.
        assertThat(order).contains("<ParameterKey>task-");
    }

    @Test
    @DisplayName("The same request on a TR-181 device takes the other path")
    void theSameSettingOnTheOtherModel() {
        post(inform181(), "s2");
        Long id = deviceStore.values().iterator().next().getId();
        acs.queue(id, CpeTask.Kind.SET_PARAMETERS,
                Map.of("settings", Map.of("WIFI_PASSWORD", "newsecret")), "admin");

        String order = post(null, "s2").body();

        assertThat(order).contains("Device.WiFi.AccessPoint.1.Security.KeyPassphrase");
    }

    @Test
    @DisplayName("A boolean is sent as a boolean, not as the word true")
    void typesAreRight() {
        post(inform098(), "s1");
        acs.queue(1L, CpeTask.Kind.SET_PARAMETERS,
                Map.of("settings", Map.of("WIFI_ENABLED", "true")), "admin");

        String order = post(null, "s1").body();

        // A CPE rejects a boolean sent as xsd:string, and the fault it returns
        // says nothing about types.
        assertThat(order).contains("xsi:type=\"xsd:boolean\"");
        assertThat(order).contains(">1</Value>");
    }

    @Test
    @DisplayName("A value with an ampersand in it does not break the document")
    void valuesAreEscaped() {
        post(inform098(), "s1");
        acs.queue(1L, CpeTask.Kind.SET_PARAMETERS,
                Map.of("settings", Map.of("WIFI_SSID", "Tom & Jerry's <WiFi>")), "admin");

        String order = post(null, "s1").body();

        // An unescaped ampersand is a document the CPE cannot parse, and what it
        // returns for that says nothing about punctuation.
        assertThat(order).contains("Tom &amp; Jerry&apos;s &lt;WiFi&gt;");
    }

    @Test
    @DisplayName("A setting this device has no path for fails with a readable reason")
    void anImpossibleSettingFailsClearly() {
        post(inform098(), "s1");
        // A device whose model we never worked out.
        deviceStore.values().iterator().next().setDataModel(CpeDevice.DataModel.TR098);
        CpeTask task = acs.queue(1L, CpeTask.Kind.SET_PARAMETERS,
                Map.of("settings", Map.of("NOT_A_REAL_SETTING", "x")), "admin");

        AcsService.Reply reply = post(null, "s1");

        assertThat(taskStore.get(task.getId()).getStatus()).isEqualTo(CpeTask.Status.FAILED);
        assertThat(taskStore.get(task.getId()).getFault()).contains("no path");
        assertThat(reply.status()).isEqualTo(204);
    }

    @Test
    @DisplayName("A device whose model we cannot tell gets no guessed path")
    void anUnknownModelYieldsNoPath() {
        // Found by a mutation run: making path() guess TR-098 for an unknown
        // model failed nothing, because the probe in nextOrder runs first and the
        // guess was never reached. Asserted here directly.
        //
        // Guessing would be right most of the time and silently wrong the rest,
        // and silently wrong means telling a customer their password changed when
        // it did not.
        assertThat(CpeParameters.path(CpeDevice.DataModel.UNKNOWN,
                CpeParameters.Setting.WIFI_PASSWORD)).isNull();
        assertThat(CpeParameters.path(null, CpeParameters.Setting.WIFI_PASSWORD)).isNull();
        // And the two known models each give their own.
        assertThat(CpeParameters.path(CpeDevice.DataModel.TR098,
                CpeParameters.Setting.WIFI_PASSWORD))
                .isEqualTo("InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.KeyPassphrase");
        assertThat(CpeParameters.path(CpeDevice.DataModel.TR181,
                CpeParameters.Setting.WIFI_PASSWORD))
                .isEqualTo("Device.WiFi.AccessPoint.1.Security.KeyPassphrase");
    }

    @Test
    @DisplayName("A device reporting both roots is treated as TR-181")
    void bothRootsMeansTr181() {
        // TR-181 has an optional InternetGatewayDevice compatibility branch, so a
        // device can honestly report both. Its native paths are the ones that
        // will not be deprecated out from under us.
        assertThat(CpeParameters.detect(List.of(
                "InternetGatewayDevice.DeviceInfo.SoftwareVersion",
                "Device.DeviceInfo.SoftwareVersion")))
                .isEqualTo(CpeDevice.DataModel.TR181);
        assertThat(CpeParameters.detect(List.of())).isEqualTo(CpeDevice.DataModel.UNKNOWN);
        assertThat(CpeParameters.detect(null)).isEqualTo(CpeDevice.DataModel.UNKNOWN);
    }

    @Test
    @DisplayName("A raw path is allowed for anything the named list does not cover")
    void rawPathsWork() {
        post(inform098(), "s1");
        acs.queue(1L, CpeTask.Kind.SET_PARAMETERS,
                Map.of("raw", Map.of("InternetGatewayDevice.Time.NTPServer1", "pool.ntp.org")),
                "admin");

        String order = post(null, "s1").body();

        // An operator who knows the exact parameter should not be blocked by this
        // codebase not having heard of it.
        assertThat(order).contains("InternetGatewayDevice.Time.NTPServer1");
        assertThat(order).contains("pool.ntp.org");
    }

    // ----------------------------------------------------------- reading values

    @Test
    @DisplayName("Values a device reports are kept")
    void reportedValuesAreStored() {
        post(inform098(), "s1");
        acs.queue(1L, CpeTask.Kind.GET_PARAMETERS,
                Map.of("names", List.of("InternetGatewayDevice.LANDevice.1."
                        + "WLANConfiguration.1.SSID")), "admin");
        post(null, "s1");

        post("""
                <?xml version="1.0"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                 xmlns:cwmp="urn:dslforum-org:cwmp-1-0">
                <soap:Header><cwmp:ID>9</cwmp:ID></soap:Header>
                <soap:Body><cwmp:GetParameterValuesResponse><ParameterList>
                <ParameterValueStruct>
                  <Name>InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID</Name>
                  <Value xsi:type="xsd:string">Kiumbe WiFi</Value>
                </ParameterValueStruct></ParameterList>
                </cwmp:GetParameterValuesResponse></soap:Body></soap:Envelope>""", "s1");

        assertThat(paramStore.values()).anyMatch(p ->
                "Kiumbe WiFi".equals(p.getValue()));
    }

    @Test
    @DisplayName("A device whose model we cannot tell is asked before anything else")
    void anUnknownModelIsProbedFirst() {
        // No parameter list on this Inform, so nothing gives the model away.
        String bare = """
                <?xml version="1.0"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                 xmlns:cwmp="urn:dslforum-org:cwmp-1-0">
                <soap:Body><cwmp:Inform><DeviceId><OUI>AABBCC</OUI>
                <SerialNumber>SN-NEW</SerialNumber></DeviceId>
                <Event><EventStruct><EventCode>0 BOOTSTRAP</EventCode></EventStruct></Event>
                </cwmp:Inform></soap:Body></soap:Envelope>""";
        post(bare, "s3");
        acs.queue(deviceStore.values().iterator().next().getId(),
                CpeTask.Kind.SET_PARAMETERS,
                Map.of("settings", Map.of("WIFI_SSID", "x")), "admin");

        String order = post(null, "s3").body();

        // Guessing TR-098 because it is commoner would be right most of the time
        // and silently wrong the rest -- and silently wrong means telling a
        // customer their password changed when it did not.
        assertThat(order).contains("GetParameterValues");
        assertThat(order).contains("InternetGatewayDevice.DeviceInfo.SoftwareVersion");
        assertThat(order).contains("Device.DeviceInfo.SoftwareVersion");
    }

    // -------------------------------------------------------------- the helpers

    private static String rebootResponse() {
        return """
                <?xml version="1.0"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                 xmlns:cwmp="urn:dslforum-org:cwmp-1-0">
                <soap:Body><cwmp:RebootResponse/></soap:Body></soap:Envelope>""";
    }

    private static String fault(String code, String message) {
        return """
                <?xml version="1.0"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                 xmlns:cwmp="urn:dslforum-org:cwmp-1-0">
                <soap:Body><soap:Fault>
                  <faultcode>Client</faultcode><faultstring>CWMP fault</faultstring>
                  <detail><cwmp:Fault><FaultCode>%s</FaultCode>
                  <FaultString>%s</FaultString></cwmp:Fault></detail>
                </soap:Fault></soap:Body></soap:Envelope>""".formatted(code, message);
    }
}
