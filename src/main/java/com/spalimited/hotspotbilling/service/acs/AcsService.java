package com.spalimited.hotspotbilling.service.acs;

import com.spalimited.hotspotbilling.domain.CpeDevice;
import com.spalimited.hotspotbilling.domain.CpeParameter;
import com.spalimited.hotspotbilling.domain.CpeTask;
import com.spalimited.hotspotbilling.repository.CpeDeviceRepository;
import com.spalimited.hotspotbilling.repository.CpeParameterRepository;
import com.spalimited.hotspotbilling.repository.CpeTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ACS: one side of a conversation the other side has to start.
 *
 * <p>TR-069 inverts the usual arrangement. The CPE is the HTTP client and this is
 * the server, but it is this that gives orders — so an order cannot be sent, only
 * left where the device will find it. A session runs
 * Inform → InformResponse → empty POST → an order → its result → another order,
 * until there is nothing left and the answer is 204.
 *
 * <p>Which is why {@link CpeTask} exists and why its states matter: an operator
 * pressing "change the WiFi password" is queuing something, and whether it has
 * actually happened is a question with a real answer that a customer can feel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcsService {

    /**
     * How long a half-finished session is remembered.
     *
     * <p>A CWMP session is a handful of round trips over seconds. Five minutes is
     * far longer than any of them and short enough that a device which drops
     * mid-session does not hold a slot until the process restarts — which on a
     * network of a few thousand CPEs is the difference between a map and a leak.
     */
    private static final Duration SESSION_TTL = Duration.ofMinutes(5);

    /** Sessions in flight, keyed on the cookie handed to the device. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final CpeDeviceRepository devices;
    private final AcsAuth acsAuth;
    private final CpeTaskRepository tasks;
    private final CpeParameterRepository parameters;
    private final ObjectMapper mapper;

    /** What we are in the middle of with one device. */
    static final class Session {
        Long deviceId;
        String namespace = Cwmp.DEFAULT_NS;
        Long inFlightTaskId;
        Instant touched = Instant.now();
    }

    /** What the endpoint should send back. */
    public record Reply(int status, String body, String sessionId) {

        static Reply xml(String body, String sessionId) {
            return new Reply(200, body, sessionId);
        }

        /** 204 ends the session: the device stops posting and goes away. */
        static Reply done(String sessionId) {
            return new Reply(204, null, sessionId);
        }
    }

    /**
     * Handles one POST from a CPE.
     *
     * <p>Never throws. A CPE that gets an exception page back does not report it
     * anywhere a human will see; it retries, fails again, and the device is simply
     * absent from the ACS with nobody knowing why. So a malformed request ends the
     * session politely and says so in the log.
     */
    @Transactional
    public Reply handle(byte[] body, String sessionId, String remoteAddress) {
        // The id first, then the session for that id. Reversed, first contact
        // built one Session for this request and newSessionId() stored a
        // different, empty one under the cookie it handed back -- so the device
        // it had just identified was written to an object nobody could retrieve,
        // and the next request answered "no orders" no matter what was queued.
        String id = sessionId == null || sessionId.isBlank() ? newSessionId() : sessionId;
        Session session = sessionFor(id);

        Cwmp.Message message;
        try {
            message = Cwmp.read(body);
        } catch (Exception e) {
            log.warn("A CPE at {} sent something unreadable: {}", remoteAddress, e.getMessage());
            return Reply.done(id);
        }

        if (message.isEmpty()) {
            // The empty POST: "I have nothing more". This is the cue to give an
            // order, and treating it as a bad request ends every session before
            // anything useful happens.
            return nextOrder(session, id);
        }

        session.namespace = message.namespace();
        session.touched = Instant.now();

        return switch (message.type()) {
            case "Inform" -> onInform(message, session, id, remoteAddress);
            case "GetParameterValuesResponse" -> onValues(message, session, id);
            case "SetParameterValuesResponse", "RebootResponse",
                 "FactoryResetResponse", "DownloadResponse" -> onAcknowledged(session, id);
            case "Fault" -> onFault(message, session, id);
            case "TransferComplete" ->
                // A firmware download finished, possibly long after the session
                // that started it. Acknowledged so the device stops repeating it.
                    Reply.xml(transferCompleteResponse(session.namespace, message.id()), id);
            default -> {
                log.debug("Ignoring a {} from a CPE", message.type());
                yield nextOrder(session, id);
            }
        };
    }

    // ------------------------------------------------------------------ Inform

    private Reply onInform(Cwmp.Message message, Session session, String sessionId,
                           String remoteAddress) {
        Cwmp.Inform inform = Cwmp.readInform(message.document());
        if (inform.serial() == null || inform.serial().isBlank()) {
            // Without a serial there is nothing to file this against, and filing
            // it against anything else would attribute one customer's box to
            // another.
            log.warn("A CPE at {} sent an Inform with no serial number", remoteAddress);
            return Reply.done(sessionId);
        }
        String oui = inform.oui() == null || inform.oui().isBlank() ? "unknown" : inform.oui();

        CpeDevice known = devices.findByOuiAndSerialNumber(oui, inform.serial()).orElse(null);
        if (known == null && !acsAuth.settings().isAllowUnknown()) {
            // Correct credentials, unexpected serial. On an estate that is
            // already in, that is either a mistake or somebody probing, and
            // filing it would put a device in the inventory that nobody bought.
            log.warn("Refused an unknown CPE {}/{} from {} — new devices are not being accepted",
                    oui, inform.serial(), remoteAddress);
            return Reply.done(sessionId);
        }
        CpeDevice device = known != null ? known
                : CpeDevice.builder().oui(oui).serialNumber(inform.serial()).build();

        device.setManufacturer(orKeep(inform.manufacturer(), device.getManufacturer()));
        device.setProductClass(orKeep(inform.productClass(), device.getProductClass()));
        device.setRemoteAddress(remoteAddress);
        device.setLastInformAt(Instant.now());
        device.setLastEvent(String.join(", ", inform.events()));

        // Read off the paths the device volunteered rather than asked for
        // separately. Every Inform carries a parameter list and its paths already
        // say which model this is -- one fewer round trip on first contact, which
        // is the contact most likely to be over a bad link.
        CpeDevice.DataModel detected = CpeParameters.detect(inform.parameters().keySet());
        if (detected != CpeDevice.DataModel.UNKNOWN) {
            device.setDataModel(detected);
        }
        applyKnownParameters(device, inform.parameters());
        devices.save(device);
        storeParameters(device.getId(), inform.parameters());

        session.deviceId = device.getId();
        return Reply.xml(Cwmp.informResponse(session.namespace, message.id()), sessionId);
    }

    /** Fields the device tells us about itself, wherever its model keeps them. */
    private void applyKnownParameters(CpeDevice device, Map<String, String> reported) {
        for (Map.Entry<String, String> entry : reported.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (name.endsWith(".DeviceInfo.SoftwareVersion")) {
                device.setSoftwareVersion(value);
            } else if (name.endsWith(".DeviceInfo.HardwareVersion")) {
                device.setHardwareVersion(value);
            } else if (name.endsWith(".ManagementServer.ConnectionRequestURL")) {
                // The device tells us where to poke it; we never guess this. A
                // CPE behind CGNAT reports a URL nothing outside can reach, which
                // is a real limit worth seeing rather than working around.
                device.setConnectionRequestUrl(value);
            }
        }
    }

    // ------------------------------------------------------------- the results

    private Reply onValues(Cwmp.Message message, Session session, String sessionId) {
        if (session.deviceId != null) {
            Map<String, String> values = Cwmp.readParameterValues(message.document());
            storeParameters(session.deviceId, values);
            devices.findById(session.deviceId).ifPresent(device -> {
                // A probe answered. Now we know which model it speaks, and every
                // later path depends on it.
                if (device.getDataModel() == CpeDevice.DataModel.UNKNOWN) {
                    CpeDevice.DataModel detected = CpeParameters.detect(values.keySet());
                    if (detected != CpeDevice.DataModel.UNKNOWN) {
                        device.setDataModel(detected);
                        devices.save(device);
                    }
                }
            });
        }
        finish(session, CpeTask.Status.DONE, null);
        return nextOrder(session, sessionId);
    }

    private Reply onAcknowledged(Session session, String sessionId) {
        finish(session, CpeTask.Status.DONE, null);
        return nextOrder(session, sessionId);
    }

    private Reply onFault(Cwmp.Message message, Session session, String sessionId) {
        Cwmp.Fault fault = Cwmp.readFault(message.document());
        String detail = fault == null ? "unknown fault"
                : (fault.code() == null ? "" : fault.code() + " ") + orKeep(fault.message(), "");
        // Recorded against the task rather than swallowed. "9005 Invalid parameter
        // name" is the difference between a mystery and a wrong data model, and it
        // is exactly the message an operator needs to see.
        finish(session, CpeTask.Status.FAILED, detail.trim());
        return nextOrder(session, sessionId);
    }

    private void finish(Session session, CpeTask.Status status, String fault) {
        if (session.inFlightTaskId == null) {
            return;
        }
        tasks.findById(session.inFlightTaskId).ifPresent(task -> {
            task.setStatus(status);
            task.setCompletedAt(Instant.now());
            task.setFault(fault);
            tasks.save(task);
        });
        session.inFlightTaskId = null;
    }

    // ------------------------------------------------------------- the orders

    /**
     * Hands the device its next job, or ends the session.
     *
     * <p>One at a time. CWMP allows several envelopes in flight and no CPE in the
     * field does it well, and a batch that half-applies is harder to reason about
     * than a queue that drains slowly.
     */
    private Reply nextOrder(Session session, String sessionId) {
        if (session.deviceId == null) {
            return Reply.done(sessionId);
        }
        CpeDevice device = devices.findById(session.deviceId).orElse(null);
        if (device == null) {
            return Reply.done(sessionId);
        }

        // A device we cannot address is worth one question before anything else,
        // because every other order depends on knowing its data model.
        if (device.getDataModel() == CpeDevice.DataModel.UNKNOWN) {
            session.inFlightTaskId = null;
            return Reply.xml(Cwmp.getParameterValues(session.namespace, newSessionId(),
                    CpeParameters.ROOT_PROBE), sessionId);
        }

        List<CpeTask> pending = tasks.findByCpeDeviceIdAndStatusOrderByIdAsc(
                device.getId(), CpeTask.Status.PENDING);
        if (pending.isEmpty()) {
            return Reply.done(sessionId);
        }
        CpeTask task = pending.get(0);
        String body = render(task, device, session.namespace);
        if (body == null) {
            // Not renderable -- usually a setting this device's data model has no
            // path for. Failing it here is better than sending something the
            // device will reject, and the message says which.
            task.setStatus(CpeTask.Status.FAILED);
            task.setFault("This device's data model has no path for that setting");
            task.setCompletedAt(Instant.now());
            tasks.save(task);
            return nextOrder(session, sessionId);
        }
        task.setStatus(CpeTask.Status.SENT);
        task.setSentAt(Instant.now());
        tasks.save(task);
        session.inFlightTaskId = task.getId();
        return Reply.xml(body, sessionId);
    }

    /**
     * One task as the CWMP request it becomes.
     *
     * <p>The payload shapes, which the migration promises are documented
     * somewhere: SET_PARAMETERS is {@code {"settings":{"WIFI_SSID":"..."}}} or
     * {@code {"raw":{"Full.Path":"value"}}}; GET_PARAMETERS is
     * {@code {"names":["..."]}}; DOWNLOAD is {@code {"url":"...","size":123}};
     * REBOOT and FACTORY_RESET carry nothing.
     */
    String render(CpeTask task, CpeDevice device, String namespace) {
        String id = "task-" + task.getId();
        JsonNode payload = readPayload(task);
        return switch (task.getKind()) {
            case REBOOT -> Cwmp.reboot(namespace, id, id);
            case FACTORY_RESET -> Cwmp.factoryReset(namespace, id);
            case DOWNLOAD -> {
                String url = payload.path("url").asString(null);
                yield url == null || url.isBlank() ? null
                        : Cwmp.download(namespace, id, id, url,
                                payload.path("fileType").asString("1 Firmware Upgrade Image"),
                                payload.path("size").asLong(0));
            }
            case GET_PARAMETERS -> {
                List<String> names = new ArrayList<>();
                for (JsonNode name : payload.path("names")) {
                    String value = name.asString(null);
                    if (value != null && !value.isBlank()) {
                        names.add(value);
                    }
                }
                if (names.isEmpty()) {
                    names.addAll(CpeParameters.interestingPaths(device.getDataModel()));
                }
                yield names.isEmpty() ? null
                        : Cwmp.getParameterValues(namespace, id, names);
            }
            case SET_PARAMETERS -> {
                List<Cwmp.Param> params = new ArrayList<>();
                // Named settings, translated to whichever path this device wants.
                JsonNode settings = payload.path("settings");
                for (String key : namesOf(settings)) {
                    CpeParameters.Setting setting = settingOf(key);
                    if (setting == null) {
                        continue;
                    }
                    String path = CpeParameters.path(device.getDataModel(), setting);
                    if (path == null) {
                        continue;
                    }
                    params.add(paramFor(setting, path, settings.path(key).asString("")));
                }
                // Raw paths, for anything the named list does not cover. An
                // operator who knows the exact parameter should not be blocked by
                // this class not having heard of it.
                JsonNode raw = payload.path("raw");
                for (String path : namesOf(raw)) {
                    params.add(Cwmp.Param.string(path, raw.path(path).asString("")));
                }
                yield params.isEmpty() ? null
                        : Cwmp.setParameterValues(namespace, id, params, id);
            }
        };
    }

    /** The right XSD type, because a CPE rejects a boolean sent as a string. */
    private static Cwmp.Param paramFor(CpeParameters.Setting setting, String path, String value) {
        return switch (setting) {
            case WIFI_ENABLED -> Cwmp.Param.bool(path,
                    "1".equals(value) || Boolean.parseBoolean(value));
            case INFORM_INTERVAL -> {
                long seconds;
                try {
                    seconds = Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    seconds = 3600;
                }
                yield Cwmp.Param.integer(path, seconds);
            }
            default -> Cwmp.Param.string(path, value);
        };
    }

    private static CpeParameters.Setting settingOf(String key) {
        try {
            return CpeParameters.Setting.valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> namesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null && node.isObject()) {
            node.propertyNames().forEach(names::add);
        }
        return names;
    }

    private JsonNode readPayload(CpeTask task) {
        if (task.getPayload() == null || task.getPayload().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(task.getPayload());
        } catch (Exception e) {
            log.warn("Task {} has an unreadable payload", task.getId());
            return mapper.createObjectNode();
        }
    }

    // ------------------------------------------------------------- the storage

    private void storeParameters(Long deviceId, Map<String, String> values) {
        if (deviceId == null || values == null || values.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.length() > 300) {
                continue;
            }
            CpeParameter row = parameters.findByCpeDeviceIdAndName(deviceId, name)
                    .orElseGet(() -> CpeParameter.builder()
                            .cpeDeviceId(deviceId).name(name).build());
            String value = entry.getValue();
            row.setValue(value == null || value.length() <= 1000 ? value
                    : value.substring(0, 1000));
            row.setUpdatedAt(now);
            parameters.save(row);
        }
    }

    // ------------------------------------------------------------- the sessions

    private Session sessionFor(String sessionId) {
        expireOldSessions();
        if (sessionId == null || sessionId.isBlank()) {
            return new Session();
        }
        return sessions.computeIfAbsent(sessionId, k -> new Session());
    }

    private String newSessionId() {
        String id = UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, new Session());
        return id;
    }

    /** Keeps the map from being a leak on a network of a few thousand CPEs. */
    private void expireOldSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.entrySet().removeIf(e -> e.getValue().touched.isBefore(cutoff));
    }

    /** Remembers a session under the id actually handed back to the device. */
    void remember(String sessionId, Session session) {
        sessions.put(sessionId, session);
    }

    private static String transferCompleteResponse(String namespace, String id) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="%s" xmlns:cwmp="%s">
                <soap:Header><cwmp:ID soap:mustUnderstand="1">%s</cwmp:ID></soap:Header>
                <soap:Body><cwmp:TransferCompleteResponse/></soap:Body>
                </soap:Envelope>""".formatted(Cwmp.SOAP_ENV, namespace,
                com.spalimited.hotspotbilling.service.SafeXml.escape(id == null ? "1" : id));
    }

    private static String orKeep(String incoming, String existing) {
        return incoming == null || incoming.isBlank() ? existing : incoming;
    }

    // --------------------------------------------------------------- the queue

    /** Queues something for a device, to happen at its next contact. */
    @Transactional
    public CpeTask queue(Long deviceId, CpeTask.Kind kind, Map<String, Object> payload,
                         String requestedBy) {
        String json = payload == null || payload.isEmpty() ? null
                : mapper.writeValueAsString(payload);
        return tasks.save(CpeTask.builder()
                .cpeDeviceId(deviceId).kind(kind).payload(json)
                .status(CpeTask.Status.PENDING).requestedBy(requestedBy)
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<CpeDevice> device(Long id) {
        return devices.findById(id);
    }

    @Transactional(readOnly = true)
    public List<CpeDevice> all() {
        return devices.findAllByOrderByLastInformAtDesc();
    }

    @Transactional(readOnly = true)
    public List<CpeTask> tasksFor(Long deviceId) {
        return tasks.findByCpeDeviceIdOrderByIdDesc(deviceId);
    }

    @Transactional(readOnly = true)
    public Map<String, String> parametersFor(Long deviceId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (CpeParameter row : parameters.findByCpeDeviceId(deviceId)) {
            out.put(row.getName(), row.getValue());
        }
        return out;
    }
}
