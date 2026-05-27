package com.vault.service;

import com.vault.dto.CreateItemRequest;
import com.vault.dto.ItemResponse;
import com.vault.entity.Item;
import com.vault.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository itemRepository;

    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public List<ItemResponse> listItems() {
        return itemRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
            .stream().map(this::toResponse).toList();
    }

    public List<ItemResponse> searchItems(String search) {
        List<Item> all = itemRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        if (search == null || search.isBlank()) return all.stream().map(this::toResponse).toList();
        String q = search.toLowerCase();
        return all.stream().filter(i ->
            i.getItemId().toLowerCase().contains(q) ||
            (i.getItemNameEn() != null && i.getItemNameEn().toLowerCase().contains(q)) ||
            (i.getItemNameTh() != null && i.getItemNameTh().toLowerCase().contains(q))
        ).map(this::toResponse).toList();
    }

    public Optional<ItemResponse> getItem(String itemId) {
        return itemRepository.findByItemIdAndDeletedAtIsNull(itemId)
            .map(this::toResponse);
    }

    @Transactional
    public ItemResponse createItem(CreateItemRequest req) {
        log.info("[ITEM] Creating item {}", req.itemId());
        if (itemRepository.existsByItemIdAndDeletedAtIsNull(req.itemId())) {
            throw new IllegalArgumentException("Item ID already exists: " + req.itemId());
        }
        Item item = new Item();
        item.setItemId(req.itemId());
        item.setItemNameEn(req.itemNameEn());
        item.setItemNameTh(req.itemNameTh());
        item.setItemStatus(req.itemStatus());
        item.setItemImageUrl(req.itemImageUrl() != null ? req.itemImageUrl() : "");
        item.setGameCode(req.gameCode());
        item.setPlayerCountMin(req.playerCountMin());
        item.setPlayerCountMax(req.playerCountMax());
        item.setDifficultyRating(req.difficultyRating());
        item.setPlayTimeMin(req.playTimeMin());
        item.setPlayTimeMax(req.playTimeMax());
        item.setLinkBoardgamegeek(req.linkBoardgamegeek());
        item.setLinkPictures(req.linkPictures());
        item.setLinkVideo(req.linkVideo());
        item.setRemark1(req.remark1());
        item.setRemark2(req.remark2());
        item.setRemark3(req.remark3());
        item.setRemark4(req.remark4());
        item.setRemark5(req.remark5());
        return toResponse(itemRepository.save(item));
    }

    @Transactional
    public ItemResponse updateItem(String itemId, String itemNameEn, String itemNameTh,
                                   String itemStatus, String gameCode,
                                   Integer playerCountMin, Integer playerCountMax,
                                   Double difficultyRating,
                                   Integer playTimeMin, Integer playTimeMax,
                                   String linkBoardgamegeek, String linkVideo, String remark1,
                                   String defaultPin) {
        log.info("[ITEM] Updating item {}", itemId);
        Item item = itemRepository.findByItemIdAndDeletedAtIsNull(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        if (itemNameEn  != null) item.setItemNameEn(itemNameEn);
        if (itemNameTh  != null) item.setItemNameTh(itemNameTh);
        if (itemStatus  != null) item.setItemStatus(itemStatus);
        item.setGameCode(gameCode);
        item.setPlayerCountMin(playerCountMin);
        item.setPlayerCountMax(playerCountMax);
        item.setDifficultyRating(difficultyRating);
        item.setPlayTimeMin(playTimeMin);
        item.setPlayTimeMax(playTimeMax);
        item.setLinkBoardgamegeek(linkBoardgamegeek);
        item.setLinkVideo(linkVideo);
        item.setRemark1(remark1);
        // defaultPin: null หรือ blank → clear, มีค่า → save
        item.setDefaultPin(defaultPin != null && !defaultPin.isBlank() ? defaultPin.trim() : null);
        return toResponse(itemRepository.save(item));
    }

    @Transactional
    public ItemResponse deleteItem(String itemId) {
        log.info("[ITEM] Deleting item {}", itemId);
        Item item = itemRepository.findByItemIdAndDeletedAtIsNull(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        item.setDeletedAt(LocalDateTime.now());
        return toResponse(itemRepository.save(item));
    }

    private ItemResponse toResponse(Item i) {
        return new ItemResponse(
            i.getId(), i.getItemId(), i.getItemNameEn(), i.getItemNameTh(),
            i.getItemStatus(), i.getItemImageUrl(),
            i.getGameCode(), i.getPlayerCountMin(), i.getPlayerCountMax(),
            i.getDifficultyRating(), i.getPlayTimeMin(), i.getPlayTimeMax(),
            i.getLinkBoardgamegeek(), i.getLinkPictures(), i.getLinkVideo(),
            i.getRemark1(), i.getRemark2(), i.getRemark3(), i.getRemark4(), i.getRemark5(),
            i.getDefaultPin(),
            i.getCreatedAt()
        );
    }
}
