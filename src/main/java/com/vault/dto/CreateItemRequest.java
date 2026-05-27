package com.vault.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateItemRequest(
    @NotBlank String itemId,
    @NotBlank String itemNameEn,
    @NotBlank String itemNameTh,
    @NotBlank String itemStatus,
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
    String defaultPin
) {}
