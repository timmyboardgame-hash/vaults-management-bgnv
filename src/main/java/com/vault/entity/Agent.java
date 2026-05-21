package com.vault.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "agent_id", unique = true, nullable = false)
    private String agentId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "agent_status", nullable = false)
    private String agentStatus;

    @Column(name = "phone")
    private String phone;

    @Column(name = "address")
    private String address;

    @Column(name = "province_code")
    private String provinceCode;

    @Column(name = "map_url")
    private String mapUrl;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "promotion")
    private String promotion;

    @Column(name = "remark_1")
    private String remark1;

    @Column(name = "remark_2")
    private String remark2;

    @Column(name = "remark_3")
    private String remark3;

    @Column(name = "remark_4")
    private String remark4;

    @Column(name = "remark_5")
    private String remark5;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "agent", fetch = FetchType.LAZY)
    private List<Booking> bookings;

    public Agent() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getAgentStatus() { return agentStatus; }
    public void setAgentStatus(String agentStatus) { this.agentStatus = agentStatus; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getProvinceCode() { return provinceCode; }
    public void setProvinceCode(String provinceCode) { this.provinceCode = provinceCode; }

    public String getMapUrl() { return mapUrl; }
    public void setMapUrl(String mapUrl) { this.mapUrl = mapUrl; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getPromotion() { return promotion; }
    public void setPromotion(String promotion) { this.promotion = promotion; }

    public String getRemark1() { return remark1; }
    public void setRemark1(String remark1) { this.remark1 = remark1; }

    public String getRemark2() { return remark2; }
    public void setRemark2(String remark2) { this.remark2 = remark2; }

    public String getRemark3() { return remark3; }
    public void setRemark3(String remark3) { this.remark3 = remark3; }

    public String getRemark4() { return remark4; }
    public void setRemark4(String remark4) { this.remark4 = remark4; }

    public String getRemark5() { return remark5; }
    public void setRemark5(String remark5) { this.remark5 = remark5; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<Booking> getBookings() { return bookings; }
    public void setBookings(List<Booking> bookings) { this.bookings = bookings; }
}
