package com.vault.dto;

import java.time.LocalDateTime;

public record ItemResponse(
    String id,
    String itemId,
    String itemNameEn,
    String itemNameTh,
    String itemStatus,
    String itemImageUrl,
    String gameCode,
    Integer playerCountMin,
    Integer playerCountMax,
    Double difficultyRating,
    Integer playTimeMin,
    Integer playTimeMax,
    String linkBoardgamegeek,
    String linkPictures,
    String linkVideo,
    String remark1,
    String remark2,
    String remark3,
    String remark4,
    String remark5,
    LocalDateTime createdAt
) {}
