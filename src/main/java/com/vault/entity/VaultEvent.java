package com.vault.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * error / เหตุการณ์ระดับระบบที่ตู้รายงานเข้ามา — topic vault/{thing}/events/system/+ (สัญญา MQTT §4.10)
 *
 * ของเดิม endpoint รับแล้ว log ทิ้งอย่างเดียว ย้อนดูไม่ได้ว่าตู้ไหนพังบ่อยเรื่องอะไร
 * ตารางนี้เป็น "ของดิบ" เหมือน booking_status_events — เขียนครั้งเดียวตอนรับ ไม่แก้ทีหลัง
 *
 * vault เป็น null ได้ เมื่อ callback ส่งชื่อ thing ที่ยังไม่มีตู้ไหนในระบบผูกไว้
 * (เช่น vault-screenless-001 ที่มีอยู่บน AWS แต่ยังไม่ได้ลงทะเบียน) — เก็บไว้ก่อนดีกว่าทิ้ง
 */
@Entity
@Table(name = "vault_events",
    indexes = {
        @Index(name = "idx_vault_event_vault_time", columnList = "vault_id, occurred_at"),
        @Index(name = "idx_vault_event_time",       columnList = "occurred_at"),
        @Index(name = "idx_vault_event_request",    columnList = "request_id")
    })
public class VaultEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id")
    private Vault vault;

    /** ชื่อ thing ที่ส่งมาจริง — เก็บไว้เสมอแม้หา vault ไม่เจอ */
    @Column(name = "thing_name", nullable = false)
    private String thingName;

    @Column(name = "error_type", nullable = false)
    private String errorType;

    /** info / warning / error ตามสัญญา */
    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** request_id_echo ของคำสั่งที่ทำให้เกิด event — null ได้ถ้าไม่ได้มาจากคำสั่ง */
    @Column(name = "request_id")
    private String requestId;

    /**
     * เวลาที่เกิด — payload จาก Lambda ยังไม่ส่ง ts มา จึงใช้เวลาที่ backend รับแทน
     * ต่างกันไม่เกินหลักวินาทีในทางปฏิบัติ ถ้าวันหลัง Lambda ส่ง ts มาค่อยเปลี่ยนมาใช้ของจริง
     */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }

    public String getThingName() { return thingName; }
    public void setThingName(String thingName) { this.thingName = thingName; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
