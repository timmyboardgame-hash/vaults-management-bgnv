package com.vault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling  // สำหรับ SessionBookingScheduler — ปิด session booking ที่หมดเวลา
public class VaultApplication {
    public static void main(String[] args) {
        // ต้องตั้งก่อน Spring เริ่ม — Hibernate แปลง LocalDateTime ↔ epoch millis ใน SQLite ด้วยเขตเวลาของ JVM
        // ถ้าเครื่องเป็น UTC (ค่าเริ่มต้นของ EC2) เวลาทุกแถวจะเลื่อน 7 ชม. และสถิติรายวันจะตัดวันตอน 07:00 น. เวลาไทย
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
        SpringApplication.run(VaultApplication.class, args);
    }
}
