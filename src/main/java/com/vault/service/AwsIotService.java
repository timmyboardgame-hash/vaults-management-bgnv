package com.vault.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.iotdataplane.model.GetThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.PublishRequest;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AWS IoT integration — MQTT publish via IotDataPlaneClient (HTTPS/SigV4)
 *
 * Backend → Device : publish vault/{thing}/cmd/booking/create|cancel
 * Device → Backend : events/booking/* → IoT Rules → Lambda → PATCH /api/v1/bookings/{id}/iot-event
 * Device state     : getThingShadow (Device Shadow)
 *
 * ถ้า AWS_IOT_ENDPOINT ไม่ได้ตั้งค่า → isConfigured() = false → log warning แล้วข้ามไป
 */
@Service
public class AwsIotService {

    private static final Logger log = LoggerFactory.getLogger(AwsIotService.class);

    private static final ZoneOffset BANGKOK      = ZoneOffset.ofHours(7);
    private static final ZoneId     BANGKOK_ZONE = ZoneId.of("Asia/Bangkok");

    private final IotClient          iotClient;
    private final IotDataPlaneClient dataPlane;
    private final ObjectMapper       objectMapper;

    public AwsIotService(Optional<IotClient>          iotClient,
                         Optional<IotDataPlaneClient>  dataPlane,
                         ObjectMapper                  objectMapper) {
        this.iotClient    = iotClient.orElse(null);
        this.dataPlane    = dataPlane.orElse(null);
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return dataPlane != null;
    }

    // ── Thing Registration ──────────────────────────────────────────────────

    /** Register vault เป็น IoT Thing — เรียกตอนสร้าง vault (idempotent) */
    public void registerThing(String thingName) {
        if (iotClient == null) {
            log.warn("[IoT] Not configured — skipping registerThing thing={}", thingName);
            return;
        }
        try {
            iotClient.createThing(
                software.amazon.awssdk.services.iot.model.CreateThingRequest.builder()
                    .thingName(thingName)
                    .build()
            );
            log.info("[IoT] Thing registered: {}", thingName);
        } catch (software.amazon.awssdk.awscore.exception.AwsServiceException e) {
            if ("ResourceAlreadyExistsException".equals(e.awsErrorDetails().errorCode())) {
                log.info("[IoT] Thing already exists: {}", thingName);
            } else {
                log.warn("[IoT] Failed to register thing={}: {}", thingName, e.awsErrorDetails().errorCode());
            }
        } catch (Exception e) {
            log.warn("[IoT] Failed to register thing={}: {}", thingName, e.getMessage());
        }
    }

    // ── Commands (Backend → Device) ─────────────────────────────────────────

    /**
     * Publish cmd/booking/create → kiosk (fire-and-forget)
     * Device ตอบผ่าน events/booking/created → IoT Rule → Lambda → PATCH /api/v1/bookings/{id}/iot-event
     *
     * LocalDateTime ถูก treat เป็น Bangkok local time (+07:00) ตาม MQTT contract §8
     */
    public void publishBookingCreate(String thingName, String bookingId, String requestId,
                                     String pin, String rfidTag,
                                     String gameId, String gameTitle,
                                     LocalDateTime validFrom, LocalDateTime validUntil) {
        if (!isConfigured()) {
            log.warn("[IoT] Not configured — skipping publishBookingCreate booking={}", bookingId);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("booking_id", bookingId);
        payload.put("pin",        pin);
        payload.put("tags",       List.of(rfidTag != null ? rfidTag : ""));
        payload.put("valid_from",  validFrom.atOffset(BANGKOK).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("valid_until", validUntil.atOffset(BANGKOK).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("game_id",    gameId);
        payload.put("game_title", gameTitle);
        payload.put("locker_id",  1);

        publishMqtt("vault/" + thingName + "/cmd/booking/create", requestId, payload);
        log.info("[IoT] Published booking/create booking={} thing={}", bookingId, thingName);
    }

    /**
     * Publish cmd/booking/cancel → kiosk (fire-and-forget)
     * booking ใน DB ถูก CANCELLED ก่อนแล้ว — device respond เป็น informational เท่านั้น
     */
    public void publishBookingCancel(String thingName, String bookingId, String requestId) {
        if (!isConfigured()) {
            log.warn("[IoT] Not configured — skipping publishBookingCancel booking={}", bookingId);
            return;
        }

        Map<String, Object> payload = Map.of("booking_id", bookingId);
        publishMqtt("vault/" + thingName + "/cmd/booking/cancel", requestId, payload);
        log.info("[IoT] Published booking/cancel booking={} thing={}", bookingId, thingName);
    }

    // ── Device State ────────────────────────────────────────────────────────

    /** อ่าน Device Shadow — ใช้แสดง vault status ใน UI */
    public Optional<Map<String, Object>> getThingShadow(String thingName) {
        if (dataPlane == null) {
            log.warn("[IoT] Not configured — cannot get shadow thing={}", thingName);
            return Optional.empty();
        }
        try {
            var response = dataPlane.getThingShadow(
                GetThingShadowRequest.builder().thingName(thingName).build()
            );
            String json = response.payload().asUtf8String();
            @SuppressWarnings("unchecked")
            Map<String, Object> shadow = objectMapper.readValue(json, Map.class);
            log.debug("[IoT] Shadow for thing={}: {}", thingName, json);
            return Optional.of(shadow);
        } catch (Exception e) {
            log.error("[IoT] Failed to get shadow thing={}: {}", thingName, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void publishMqtt(String topic, String requestId, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("request_id",     requestId);
            envelope.put("sent_at", ZonedDateTime.now(BANGKOK_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            envelope.put("payload",        payload);

            String json = objectMapper.writeValueAsString(envelope);

            log.info("[IoT] >>> PUBLISH topic={} payload={}", topic, json);

            dataPlane.publish(PublishRequest.builder()
                .topic(topic)
                .qos(1)
                .payload(SdkBytes.fromUtf8String(json))
                .build());

            log.info("[IoT] >>> PUBLISH OK topic={}", topic);
        } catch (Exception e) {
            log.error("[IoT] >>> PUBLISH FAILED topic={} reason={}", topic, e.getMessage(), e);
        }
    }
}
