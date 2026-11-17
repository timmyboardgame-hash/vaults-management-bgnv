package com.vault.repository;

import com.vault.entity.Agent;
import com.vault.entity.Booking;
import com.vault.entity.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * กติกาจองซ้อนอยู่ใน JPQL — mock ทดสอบไม่ได้ ต้องยิง query จริงบน SQLite ตัวเดียวกับ production
 *
 * บั๊กเดิม: เช็คแค่ "กล่องนี้มีใบค้างไหม" ไม่ดูเวลา มีใบจอง 19:00 อยู่ ก็จอง 16:00 ไม่ได้
 * (เจอจริง: 99767087-… จองล่วงหน้าวันที่ 19 ล็อกกล่อง NFC-04332A7AD42A81 ตั้งแต่วันที่ 15)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BookingConflictQueryTest {

    private static final String SERIAL = "NFC-04332A7AD42A81";
    private static final LocalDateTime DAY = LocalDateTime.of(2030, 1, 15, 0, 0);

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry r) throws Exception {
        Path db = Files.createTempFile("booking-conflict-", ".db");
        db.toFile().deleteOnExit();
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + db);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired TestEntityManager em;
    @Autowired BookingRepository bookings;

    private Agent agent;
    private Item item;

    @BeforeEach
    void seed() {
        agent = new Agent();
        agent.setAgentId("shop-1");
        agent.setAgentName("ร้านทดสอบ");
        agent.setAgentStatus("ENABLE");
        em.persist(agent);

        item = new Item();
        item.setItemId("NOVA-EVENT-002");
        item.setItemNameEn("Nova");
        item.setItemNameTh("โนวา");
        item.setItemImageUrl("-");
        item.setItemStatus("ACTIVE");
        em.persist(item);
    }

    private Booking booked(String id, String status, int fromHour, int toHour) {
        Booking b = new Booking();
        b.setBookingId(id);
        b.setAgent(agent);
        b.setItem(item);
        b.setSerialNumber(SERIAL);
        b.setBookingStatus(status);
        b.setBookingTimeStart(DAY.withHour(fromHour));
        b.setBookingTimeEnd(DAY.withHour(toHour));
        em.persist(b);
        em.flush();
        return b;
    }

    private List<String> conflictsFor(int fromHour, int toHour) {
        return bookings.findConflicting(item.getId(), SERIAL, DAY.withHour(fromHour), DAY.withHour(toHour))
                .stream().map(Booking::getBookingId).toList();
    }

    // ───────────────────────────── เคสที่เป็นบั๊ก ─────────────────────────────

    /** เคสที่ถูกแจ้งมาตรง ๆ — มีใบ 19:00 อยู่ ต้องจอง 16:00 ได้ */
    @Test
    void anEveningBookingDoesNotBlockAnAfternoonOne() {
        booked("BK-EVENING", "CONFIRMED", 19, 21);

        assertThat(conflictsFor(16, 18)).isEmpty();
    }

    /** กลับด้านก็ต้องได้ — ใบบ่ายไม่ขวางใบค่ำ */
    @Test
    void anAfternoonBookingDoesNotBlockAnEveningOne() {
        booked("BK-AFTERNOON", "CONFIRMED", 16, 18);

        assertThat(conflictsFor(19, 21)).isEmpty();
    }

    /** ต่อกันพอดี 16–17 กับ 17–18 ไม่นับว่าทับ — ไม่งั้นจองต่อกันเป็นช่วงไม่ได้ */
    @Test
    void backToBackBookingsDoNotConflict() {
        booked("BK-FIRST", "CONFIRMED", 16, 17);

        assertThat(conflictsFor(17, 18)).isEmpty();
        assertThat(conflictsFor(15, 16)).isEmpty();
    }

    // ───────────────────────────── ยังต้องกันอยู่ ─────────────────────────────

    @Test
    void blocksAPartialOverlap() {
        booked("BK-EXISTING", "CONFIRMED", 16, 18);

        assertThat(conflictsFor(17, 19)).containsExactly("BK-EXISTING");
        assertThat(conflictsFor(15, 17)).containsExactly("BK-EXISTING");
    }

    @Test
    void blocksAWindowInsideAnExistingOne() {
        booked("BK-LONG", "CONFIRMED", 12, 20);

        assertThat(conflictsFor(14, 15)).containsExactly("BK-LONG");
    }

    @Test
    void blocksAWindowThatSwallowsAnExistingOne() {
        booked("BK-SHORT", "CONFIRMED", 14, 15);

        assertThat(conflictsFor(12, 20)).containsExactly("BK-SHORT");
    }

    /** ทุกสถานะที่ยังไม่จบต้องขวาง — PENDING คือตู้ยังไม่ตอบ แต่ใบมีอยู่จริง */
    @Test
    void pendingAndActiveBlockToo() {
        booked("BK-PENDING", "PENDING", 10, 12);
        booked("BK-ACTIVE", "ACTIVE", 13, 15);

        assertThat(conflictsFor(11, 14)).containsExactly("BK-PENDING", "BK-ACTIVE");
    }

    /**
     * OVERDUE ขวางทุกช่วงเวลา — เวลาจบผ่านไปแล้วแต่ของยังไม่กลับเข้าตู้
     * ถ้าใช้กฎเวลาอย่างเดียว ใบนี้จะหลุด แล้วระบบออก PIN ให้กล่องที่ไม่อยู่ในตู้
     */
    @Test
    void anOverdueBookingBlocksEveryWindow() {
        booked("BK-OVERDUE", "OVERDUE", 8, 10);

        assertThat(conflictsFor(19, 21)).containsExactly("BK-OVERDUE");
    }

    // ───────────────────────────── ใบที่จบแล้วไม่ขวาง ─────────────────────────────

    @Test
    void finishedBookingsNeverBlock() {
        for (String status : List.of("RETURNED", "CANCELLED", "FAILED", "EXPIRED")) {
            booked("BK-" + status, status, 16, 18);
        }

        assertThat(conflictsFor(16, 18)).isEmpty();
    }

    @Test
    void softDeletedBookingsNeverBlock() {
        Booking gone = booked("BK-DELETED", "CONFIRMED", 16, 18);
        gone.setDeletedAt(LocalDateTime.now());
        em.flush();

        assertThat(conflictsFor(16, 18)).isEmpty();
    }

    /** กล่องอื่นของเกมเดียวกันไม่เกี่ยว */
    @Test
    void anotherCopyOfTheSameGameIsIgnored() {
        Booking other = booked("BK-OTHER-COPY", "CONFIRMED", 16, 18);
        other.setSerialNumber("NFC-SOMETHING-ELSE");
        em.flush();

        assertThat(conflictsFor(16, 18)).isEmpty();
    }

    /** ตัวเช็คเดิมที่ใช้กันย้าย/ลบกล่องต้องยังไม่สนเวลา — มีใบค้างคือห้ามย้าย */
    @Test
    void theAnyTimeCheckForMovingCopiesIsUnchanged() {
        booked("BK-NEXT-WEEK", "CONFIRMED", 19, 21);

        assertThat(bookings.existsActiveByItemAndSerial(item.getId(), SERIAL)).isTrue();
    }
}
