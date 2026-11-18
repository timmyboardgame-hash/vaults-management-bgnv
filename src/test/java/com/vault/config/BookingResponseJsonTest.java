package com.vault.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.dto.BookingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * หน้าตา JSON ที่แอปได้รับจริง — เป็นสัญญากับทีมแอป เปลี่ยนแล้วต้องแก้เอกสารด้วย
 */
class BookingResponseJsonTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    private JsonNode json() throws Exception {
        OffsetDateTime t = OffsetDateTime.of(2030, 1, 15, 16, 0, 0, 0, ZoneOffset.ofHours(7));
        BookingResponse r = new BookingResponse(
                "BK20300115160000000", "shop-1", "ร้านทดสอบ",
                "NOVA-EVENT-002", "Nova", "โนวา", null, "vault-screenless-002",
                "PENDING", t, t.plusHours(2), "walk-in", "2030-01-15", "322619",
                "NFC-04332A7AD42A81", "b1154b4e-379e-4326-a56f-9609296441a9", t);
        return mapper.readTree(mapper.writeValueAsString(r));
    }

    /**
     * DB id ต้องไม่หลุดออกไป — เป็น UUID หน้าตาเหมือน bookingId ที่แอปส่งมาเอง
     * เคยอยู่ในก้อนเดียวกัน หยิบผิดตัวไปทำ QR หรือเรียก endpoint ต่อจะพังเงียบ ๆ
     */
    @Test
    void doesNotExposeTheDatabaseId() throws Exception {
        assertThat(json().has("id")).isFalse();
    }

    /** ลำดับและชื่อ field ตามที่เขียนไว้ในเอกสารหัวข้อ 7.4 */
    @Test
    void hasExactlyTheDocumentedFields() throws Exception {
        List<String> names = new ArrayList<>();
        json().fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactly(
                "bookingId", "agentId", "agentName", "itemId", "itemNameEn", "itemNameTh",
                "slotId", "vaultId", "bookingStatus", "bookingTimeStart", "bookingTimeEnd",
                "bookingName", "bookingDate", "pin", "serialNumber", "qrToken", "createdAt");
    }
}
