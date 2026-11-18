package com.vault.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.dto.CreateBookingRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ชื่อ field ที่แอปส่งมาต้องถูก bind จริง — ใช้ mapper ที่ตั้งค่าแบบเดียวกับที่ Spring ใช้
 *
 * ถ้าชื่อไม่ตรง Jackson ทิ้งค่าเงียบ ๆ และ API ยังตอบ 201 — ลูกค้าได้ QR ที่ตู้ไม่รู้จัก
 * ไม่มีใครรู้ตัวจนสแกนหน้าตู้แล้วไม่เปิด
 */
class QrTokenJsonBindingTest {

    private static final String BASE =
            "\"itemId\":\"NOVA-EVENT-002\",\"serialNumber\":\"NFC-1\"," +
            "\"bookingTimeStart\":\"2030-01-15T13:00:00+07:00\"," +
            "\"bookingTimeEnd\":\"2030-01-15T15:00:00+07:00\"";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(UnknownJsonFieldLogger.class);
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // builder ตัวเดียวกับที่ Spring Boot ใช้ (ปิด FAIL_ON_UNKNOWN_PROPERTIES เป็นค่าเริ่มต้น)
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new UnknownJsonFieldLogger().logUnknownJsonFields().customize(builder);
        mapper = builder.build();

        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private CreateBookingRequest parse(String extra) throws Exception {
        return mapper.readValue("{" + BASE + extra + "}", CreateBookingRequest.class);
    }

    private java.util.List<String> warnings() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void bindsCamelCaseLikeEveryOtherApiField() throws Exception {
        assertThat(parse(",\"qrToken\":\"tok-1\"").qrToken()).isEqualTo("tok-1");
        assertThat(warnings()).isEmpty();
    }

    /** payload MQTT ใช้ snake_case — แอปน่าจะหลงส่งชื่อนี้มา ต้องรับได้ ไม่ใช่ทิ้งเงียบ */
    @Test
    void alsoBindsSnakeCase() throws Exception {
        assertThat(parse(",\"qr_token\":\"tok-1\"").qrToken()).isEqualTo("tok-1");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void isOptional() throws Exception {
        assertThat(parse("").qrToken()).isNull();
    }

    /** สะกดผิด → request ยังผ่าน (ไม่ทำของเดิมพัง) แต่ต้องทิ้งร่องรอยใน log ให้ไล่ได้ */
    @Test
    void logsAMisspelledFieldInsteadOfDroppingItSilently() throws Exception {
        CreateBookingRequest req = parse(",\"qrtoken\":\"tok-1\"");

        assertThat(req.qrToken()).isNull();
        assertThat(req.itemId()).isEqualTo("NOVA-EVENT-002");
        assertThat(warnings()).containsExactly(
                "[API] Unknown field 'qrtoken' in CreateBookingRequest — ignored");
    }

    /** log แค่ชื่อ ไม่ log ค่า — field ที่ส่งผิดชื่ออาจเป็น PIN หรือ token */
    @Test
    void neverLogsTheValueOfAnUnknownField() throws Exception {
        parse(",\"secretPin\":\"9999-DO-NOT-LOG\"");

        assertThat(warnings()).singleElement().asString()
                .contains("secretPin")
                .doesNotContain("9999-DO-NOT-LOG");
    }

    /** ค่าที่เป็น object ซ้อนต้องถูกข้ามทั้งก้อน ไม่ทำให้ field ถัดไปพัง */
    @Test
    void skipsANestedUnknownObjectCleanly() throws Exception {
        CreateBookingRequest req = mapper.readValue(
                "{\"extra\":{\"a\":[1,{\"b\":2}]}," + BASE + ",\"qrToken\":\"tok-1\"}",
                CreateBookingRequest.class);

        assertThat(req.qrToken()).isEqualTo("tok-1");
        assertThat(req.serialNumber()).isEqualTo("NFC-1");
        assertThat(warnings()).hasSize(1);
    }
}
