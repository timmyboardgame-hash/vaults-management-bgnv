package com.vault.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "items")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "item_id", unique = true, nullable = false)
    private String itemId;

    @Column(name = "item_name_en", nullable = false)
    private String itemNameEn;

    @Column(name = "item_name_th", nullable = false)
    private String itemNameTh;

    @Column(name = "item_image_url", nullable = false)
    private String itemImageUrl;

    @Column(name = "item_status", nullable = false)
    private String itemStatus;

    @Column(name = "game_code")
    private String gameCode;

    @Column(name = "player_count_min")
    private Integer playerCountMin;

    @Column(name = "player_count_max")
    private Integer playerCountMax;

    @Column(name = "difficulty_rating")
    private Double difficultyRating;

    @Column(name = "play_time_min")
    private Integer playTimeMin;

    @Column(name = "play_time_max")
    private Integer playTimeMax;

    @Column(name = "link_boardgamegeek")
    private String linkBoardgamegeek;

    @Column(name = "link_pictures")
    private String linkPictures;

    @Column(name = "link_video")
    private String linkVideo;

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

    @OneToMany(mappedBy = "item", fetch = FetchType.LAZY)
    private List<Booking> bookings;

    @OneToMany(mappedBy = "item", fetch = FetchType.LAZY)
    private List<VaultItem> vaultItems;

    public Item() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public String getItemNameEn() { return itemNameEn; }
    public void setItemNameEn(String itemNameEn) { this.itemNameEn = itemNameEn; }

    public String getItemNameTh() { return itemNameTh; }
    public void setItemNameTh(String itemNameTh) { this.itemNameTh = itemNameTh; }

    public String getItemImageUrl() { return itemImageUrl; }
    public void setItemImageUrl(String itemImageUrl) { this.itemImageUrl = itemImageUrl; }

    public String getItemStatus() { return itemStatus; }
    public void setItemStatus(String itemStatus) { this.itemStatus = itemStatus; }

    public String getGameCode() { return gameCode; }
    public void setGameCode(String gameCode) { this.gameCode = gameCode; }

    public Integer getPlayerCountMin() { return playerCountMin; }
    public void setPlayerCountMin(Integer playerCountMin) { this.playerCountMin = playerCountMin; }

    public Integer getPlayerCountMax() { return playerCountMax; }
    public void setPlayerCountMax(Integer playerCountMax) { this.playerCountMax = playerCountMax; }

    public Double getDifficultyRating() { return difficultyRating; }
    public void setDifficultyRating(Double difficultyRating) { this.difficultyRating = difficultyRating; }

    public Integer getPlayTimeMin() { return playTimeMin; }
    public void setPlayTimeMin(Integer playTimeMin) { this.playTimeMin = playTimeMin; }

    public Integer getPlayTimeMax() { return playTimeMax; }
    public void setPlayTimeMax(Integer playTimeMax) { this.playTimeMax = playTimeMax; }

    public String getLinkBoardgamegeek() { return linkBoardgamegeek; }
    public void setLinkBoardgamegeek(String linkBoardgamegeek) { this.linkBoardgamegeek = linkBoardgamegeek; }

    public String getLinkPictures() { return linkPictures; }
    public void setLinkPictures(String linkPictures) { this.linkPictures = linkPictures; }

    public String getLinkVideo() { return linkVideo; }
    public void setLinkVideo(String linkVideo) { this.linkVideo = linkVideo; }

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

    public List<VaultItem> getVaultItems() { return vaultItems; }
    public void setVaultItems(List<VaultItem> vaultItems) { this.vaultItems = vaultItems; }
}
