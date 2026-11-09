package com.vault.service;

import com.vault.dto.BatchRegisterGameCopyRequest;
import com.vault.dto.BatchRegisterGameCopyResponse;
import com.vault.dto.GameCopyResponse;
import com.vault.dto.RegisterGameCopyRequest;
import com.vault.dto.UpdateGameCopyRequest;
import com.vault.entity.Agent;
import com.vault.entity.Binding;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.event.VaultInventoryChanged;
import com.vault.exception.ApiCodeException;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.BookingRepository;
import com.vault.repository.ItemRepository;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * ลงทะเบียนกล่องเกมจากแอปร้าน
 *
 * ลำดับงานจริงคือร้านลงทะเบียนตอนกล่องยังอยู่ในมือ ยังไม่ได้เอาเข้าตู้
 * เลยต้องแยก "เจ้าของ" (ร้านที่ลงทะเบียน) ออกจาก "ที่อยู่" (ตู้ที่กล่องถูกวาง)
 * กล่องที่ยังไม่มีที่อยู่เก็บได้ แต่จองไม่ได้
 *
 * รอบนี้แค่บันทึก — ยังไม่ส่ง mapping แท็ก → เกม เข้าตู้ (cmd/inventory/sync)
 * ตู้จะยังไม่รู้จักแท็กที่เพิ่งลงทะเบียน ต้องเขียนแท็กจากหน้าเครื่องเหมือนเดิมไปก่อน
 */
@Service
public class GameCopyService {

    private static final Logger log = LoggerFactory.getLogger(GameCopyService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    // EPC ของแท็กที่ตู้รองรับ — 24 hex · เจอผิดบ่อยเวลาคนพิมพ์เลขเอง
    private static final Pattern RFID_TAG = Pattern.compile("^[0-9A-Fa-f]{24}$");

    private final VaultItemRepository vaultItemRepository;
    private final VaultRepository vaultRepository;
    private final AgentRepository agentRepository;
    private final ItemRepository itemRepository;
    private final BindingRepository bindingRepository;
    private final BookingRepository bookingRepository;

    /** แจ้งว่าของในตู้เปลี่ยน — ตัว sync รอ commit แล้วส่งรายการแท็กไปให้ตู้ */
    private final org.springframework.context.ApplicationEventPublisher events;

    /**
     * ตัวเองผ่าน proxy — ต้องใช้ตอน batch เรียก register() รายกล่อง
     * ถ้าเรียก this.register() ตรง ๆ จะข้าม proxy ไป @Transactional ไม่ทำงาน
     * แล้วกล่องที่ล้มกลางทางจะทิ้ง item ที่เพิ่งสร้างค้างไว้
     */
    private final Supplier<GameCopyService> self;

    @org.springframework.beans.factory.annotation.Autowired
    public GameCopyService(VaultItemRepository vaultItemRepository,
                           VaultRepository vaultRepository,
                           AgentRepository agentRepository,
                           ItemRepository itemRepository,
                           BindingRepository bindingRepository,
                           BookingRepository bookingRepository,
                           org.springframework.context.ApplicationEventPublisher events,
                           org.springframework.beans.factory.ObjectProvider<GameCopyService> selfProvider) {
        this(vaultItemRepository, vaultRepository, agentRepository, itemRepository,
             bindingRepository, bookingRepository, events,
             (Supplier<GameCopyService>) selfProvider::getObject);
    }

    /** ใช้ในเทส — ส่ง supplier ของตัวเองเข้ามาตรง ๆ ได้ ไม่ต้องพึ่ง container */
    GameCopyService(VaultItemRepository vaultItemRepository,
                    VaultRepository vaultRepository,
                    AgentRepository agentRepository,
                    ItemRepository itemRepository,
                    BindingRepository bindingRepository,
                    BookingRepository bookingRepository,
                    org.springframework.context.ApplicationEventPublisher events,
                    Supplier<GameCopyService> self) {
        this.events = events;
        this.self = self;
        this.vaultItemRepository = vaultItemRepository;
        this.vaultRepository = vaultRepository;
        this.agentRepository = agentRepository;
        this.itemRepository = itemRepository;
        this.bindingRepository = bindingRepository;
        this.bookingRepository = bookingRepository;
    }

    /** ผลการลงทะเบียน — created บอกว่าเป็นกล่องใหม่ หรือยิงซ้ำของเดิม (คนเรียกใช้เลือก 201/200) */
    public record Registration(GameCopyResponse copy, boolean created) {}

    // ───────────────────────────── ลงทะเบียน ─────────────────────────────

    @Transactional
    public Registration register(String shopId, RegisterGameCopyRequest req) {
        Agent shop = requireShop(shopId);

        String serial = trimmed(req.serialNumber());
        if (serial == null) {
            throw ApiCodeException.badRequest("SERIAL_REQUIRED",
                    "ต้องส่ง serialNumber มาด้วย — serial คือรหัสประจำกล่อง", "serialNumber");
        }

        String rfidTag = trimmed(req.rfidTag());
        if (rfidTag == null) {
            throw ApiCodeException.badRequest("RFID_TAG_REQUIRED",
                    "ต้องส่ง rfidTag มาด้วย — แท็กถูกแปะบนกล่องตั้งแต่ตอนลงทะเบียน", "rfidTag");
        }
        if (!RFID_TAG.matcher(rfidTag).matches()) {
            throw ApiCodeException.badRequest("RFID_TAG_FORMAT",
                    "rfidTag ต้องเป็นเลขฐานสิบหก 24 ตัว (ที่ส่งมายาว " + rfidTag.length() + " ตัว)", "rfidTag");
        }

        // ยิงซ้ำด้วย serial เดิมต้องไม่พัง — แอป retry ได้โดยไม่ต้องจำ request id
        Optional<VaultItem> existing = vaultItemRepository.findBySerial(serial);
        if (existing.isPresent()) {
            return new Registration(sameOrConflict(existing.get(), shop, rfidTag, req), false);
        }

        requireTagFree(rfidTag, null);

        ItemResolution resolved = resolveItem(req.item());
        Vault vault = resolvePlacement(shop, req.vaultId());

        VaultItem copy = new VaultItem();
        copy.setSerialNumber(serial);
        copy.setRfidTag(rfidTag);
        copy.setItem(resolved.item());
        copy.setOwnerAgent(shop);
        copy.setVault(vault);
        copy.setStatus("ACTIVE");
        // flush ตรงนี้เพราะต้องอ่าน createdAt กลับไปตอบ registeredAt
        // @CreationTimestamp ลงค่าตอน flush ถ้ารอ commit จะตอบ null ออกไป
        VaultItem saved = vaultItemRepository.saveAndFlush(copy);

        log.info("[GAME_COPY] Registered serial={} tag={} shop={} item={} vault={}",
                serial, rfidTag, shopId, resolved.item().getItemId(),
                vault != null ? vault.getVaultId() : "UNASSIGNED");

        // กล่องที่ยังไม่มีตู้ไม่ต้อง sync — ยังไม่มีตู้ไหนต้องรู้จักแท็กนี้
        inventoryChanged(vault, "copy-registered");

        return new Registration(toResponse(saved, resolved.created()), true);
    }

    /**
     * ยิง serial เดิมซ้ำ — ข้อมูลตรงกันทุกอย่างถือว่าสำเร็จ คืนกล่องใบเดิม
     * ไม่ตรง (คนละร้าน คนละเกม คนละแท็ก) ต้องตีกลับ ไม่ใช่เขียนทับของเดิมเงียบ ๆ
     */
    private GameCopyResponse sameOrConflict(VaultItem copy, Agent shop, String rfidTag,
                                            RegisterGameCopyRequest req) {
        String serial = copy.getSerialNumber();
        String requestedItemId = req.item() != null ? trimmed(req.item().itemId()) : null;

        if (!Objects.equals(copy.ownerAgentId(), shop.getAgentId())) {
            throw ApiCodeException.conflict("SERIAL_ALREADY_REGISTERED",
                    "serial " + serial + " ถูกลงทะเบียนไว้แล้วกับร้านอื่น", "serialNumber");
        }
        if (requestedItemId != null && !requestedItemId.equals(copy.getItem().getItemId())) {
            throw ApiCodeException.conflict("SERIAL_ALREADY_REGISTERED",
                    "serial " + serial + " ถูกลงทะเบียนไว้แล้วกับเกม " + copy.getItem().getItemId(),
                    "serialNumber");
        }
        if (!rfidTag.equalsIgnoreCase(copy.getRfidTag())) {
            throw ApiCodeException.conflict("SERIAL_ALREADY_REGISTERED",
                    "serial " + serial + " ถูกลงทะเบียนไว้แล้วด้วยแท็กอื่น — ถ้าเปลี่ยนแท็กใหม่ให้ใช้ PATCH",
                    "rfidTag");
        }

        log.info("[GAME_COPY] Serial {} already registered with the same data — returning existing copy", serial);
        return toResponse(copy, false);
    }

    /**
     * ไม่ครอบ transaction ทั้งชุดโดยตั้งใจ — แต่ละกล่องมี transaction ของตัวเอง
     * ถ้าใช้ก้อนเดียว กล่องที่ล้มจะ rollback กล่องที่สำเร็จไปแล้วด้วย
     * ทั้งที่เราตอบแอปไปว่ากล่องนั้นลงทะเบียนสำเร็จ
     */
    public BatchRegisterGameCopyResponse registerBatch(String shopId, BatchRegisterGameCopyRequest req) {
        requireShop(shopId);
        List<RegisterGameCopyRequest> copies = req != null && req.copies() != null ? req.copies() : List.of();
        if (copies.isEmpty()) {
            throw ApiCodeException.badRequest("COPIES_REQUIRED", "ต้องส่ง copies มาอย่างน้อยหนึ่งกล่อง", "copies");
        }

        List<GameCopyResponse> ok = new ArrayList<>();
        List<BatchRegisterGameCopyResponse.Failure> failed = new ArrayList<>();
        for (RegisterGameCopyRequest one : copies) {
            if (one == null) {
                failed.add(new BatchRegisterGameCopyResponse.Failure(
                        null, "SERIAL_REQUIRED", "มีรายการว่างอยู่ในชุดที่ส่งมา"));
                continue;
            }
            try {
                ok.add(self.get().register(shopId, one).copy());
            } catch (ApiCodeException e) {
                failed.add(new BatchRegisterGameCopyResponse.Failure(
                        one.serialNumber(), e.getCode(), e.getMessage()));
            }
        }
        log.info("[GAME_COPY] Batch shop={} registered={} failed={}", shopId, ok.size(), failed.size());
        return new BatchRegisterGameCopyResponse(ok.size(), ok, failed);
    }

    // ───────────────────────────── อ่าน ─────────────────────────────

    public List<GameCopyResponse> listByShop(String shopId, String placement, String itemId) {
        requireShop(shopId);
        String wanted = trimmed(placement);
        String wantedItem = trimmed(itemId);
        return vaultItemRepository.findByOwner(shopId).stream()
                .map(vi -> toResponse(vi, false))
                .filter(c -> wanted == null || wanted.equalsIgnoreCase(c.placement()))
                .filter(c -> wantedItem == null || wantedItem.equals(c.item().itemId()))
                .toList();
    }

    public Optional<GameCopyResponse> getBySerial(String serialNumber) {
        return vaultItemRepository.findBySerial(serialNumber)
                .map(vi -> toResponse(vi, false));
    }

    // ───────────────────────────── แก้ / ลบ ─────────────────────────────

    @Transactional
    public GameCopyResponse update(String serialNumber, UpdateGameCopyRequest req) {
        VaultItem copy = requireCopy(serialNumber);
        Vault vaultBefore = copy.getVault();

        String newTag = trimmed(req.rfidTag());
        if (newTag != null && !newTag.equalsIgnoreCase(copy.getRfidTag())) {
            if (!RFID_TAG.matcher(newTag).matches()) {
                throw ApiCodeException.badRequest("RFID_TAG_FORMAT",
                        "rfidTag ต้องเป็นเลขฐานสิบหก 24 ตัว", "rfidTag");
            }
            // แท็กเป็นตัวที่ตู้ใช้ชี้กล่อง เปลี่ยนระหว่างลูกค้ายืมอยู่ = ตอนคืนตู้จะชี้ผิดใบ
            requireNoActiveBooking(copy, "เปลี่ยนแท็ก");
            requireTagFree(newTag, copy.getId());
            copy.setRfidTag(newTag);
        }

        String newVaultId = trimmed(req.vaultId());
        if (newVaultId != null && !newVaultId.equals(copy.getVault() != null ? copy.getVault().getVaultId() : null)) {
            Agent owner = copy.getOwnerAgent();
            if (owner == null) {
                throw ApiCodeException.conflict("COPY_HAS_NO_OWNER",
                        "กล่องนี้ยังไม่มีร้านเจ้าของ ย้ายตู้ผ่าน API นี้ไม่ได้ — ให้ทำจากหน้าหลังบ้าน", "vaultId");
            }
            requireNoActiveBooking(copy, "ย้ายตู้");
            copy.setVault(requireVaultOfShop(owner, newVaultId));
        }

        VaultItem saved = vaultItemRepository.save(copy);
        log.info("[GAME_COPY] Updated serial={} tag={} vault={}", serialNumber, saved.getRfidTag(),
                saved.getVault() != null ? saved.getVault().getVaultId() : "UNASSIGNED");

        // ย้ายตู้ต้อง sync ทั้งสองฝั่ง — ตู้เดิมต้องรู้ว่าของไม่อยู่แล้ว
        inventoryChanged(vaultBefore, saved.getVault(), "copy-updated");

        return toResponse(saved, false);
    }

    @Transactional
    public void delete(String serialNumber) {
        VaultItem copy = requireCopy(serialNumber);
        requireNoActiveBooking(copy, "ลบกล่อง");
        copy.setStatus("INACTIVE");
        copy.setDeletedAt(LocalDateTime.now());
        vaultItemRepository.save(copy);
        log.info("[GAME_COPY] Soft-deleted serial={}", serialNumber);

        // ถอดกล่องออกจากระบบแล้ว ตู้ต้องเลิกรู้จักแท็กนี้ด้วย
        inventoryChanged(copy.getVault(), "copy-deleted");
    }

    // ───────────────────────────── ตัวช่วย ─────────────────────────────

    /** ตู้ไหนของเปลี่ยน ตู้นั้นต้องได้รายการแท็กชุดใหม่ — ส่งจริงหลัง commit */
    private void inventoryChanged(Vault vault, String reason) {
        if (vault == null) return;
        events.publishEvent(new VaultInventoryChanged(vault.getVaultId(), reason));
    }

    /**
     * ของย้ายจากตู้หนึ่งไปอีกตู้ — ทั้งสองฝั่งต้องได้รายการใหม่
     * ตู้เดียวกัน (เช่นแก้แค่แท็ก) ส่งครั้งเดียว ไม่ต้องยิงซ้ำสองรอบให้ตู้เดิม
     */
    private void inventoryChanged(Vault before, Vault after, String reason) {
        inventoryChanged(before, reason);
        String beforeId = before != null ? before.getVaultId() : null;
        if (after != null && !after.getVaultId().equals(beforeId)) {
            inventoryChanged(after, reason);
        }
    }

    private Agent requireShop(String shopId) {
        return agentRepository.findByAgentIdAndDeletedAtIsNull(shopId)
                .orElseThrow(() -> ApiCodeException.notFound("SHOP_NOT_FOUND",
                        "ไม่พบร้านรหัส " + shopId, "shopId"));
    }

    private VaultItem requireCopy(String serialNumber) {
        return vaultItemRepository.findBySerial(serialNumber)
                .orElseThrow(() -> ApiCodeException.notFound("COPY_NOT_FOUND",
                        "ไม่พบกล่อง serial " + serialNumber, "serialNumber"));
    }

    private void requireTagFree(String rfidTag, String excludeCopyId) {
        if (vaultItemRepository.existsByRfidTag(rfidTag, excludeCopyId == null ? "" : excludeCopyId)) {
            String clash = vaultItemRepository.findByRfidTag(rfidTag)
                    .map(VaultItem::getSerialNumber).orElse("กล่องอื่น");
            throw ApiCodeException.conflict("RFID_TAG_IN_USE",
                    "แท็กนี้ถูกใช้กับกล่อง " + clash + " อยู่แล้ว", "rfidTag");
        }
    }

    private void requireNoActiveBooking(VaultItem copy, String action) {
        if (copy.getSerialNumber() == null) return;
        if (bookingRepository.existsActiveByItemAndSerial(copy.getItem().getId(), copy.getSerialNumber())) {
            throw ApiCodeException.conflict("COPY_HAS_ACTIVE_BOOKING",
                    action + "ไม่ได้ กล่อง " + copy.getSerialNumber() + " ยังติดใบจองที่ไม่จบ", "serialNumber");
        }
    }

    private record ItemResolution(Item item, boolean created) {}

    /**
     * catalog เกมอยู่ที่ฝั่งแอป — itemId ที่ยังไม่มีในระบบจะสร้างให้จากชื่อที่แนบมา
     * ของที่มีอยู่แล้วใช้ของเดิม ไม่เขียนทับชื่อหรือรายละเอียดที่หลังบ้านแก้ไว้
     */
    private ItemResolution resolveItem(RegisterGameCopyRequest.ItemPayload payload) {
        String itemId = payload != null ? trimmed(payload.itemId()) : null;
        if (itemId == null) {
            throw ApiCodeException.badRequest("ITEM_FIELDS_REQUIRED",
                    "ต้องส่ง item.itemId มาด้วย", "item.itemId");
        }

        Optional<Item> existing = itemRepository.findByItemIdAndDeletedAtIsNull(itemId);
        if (existing.isPresent()) {
            return new ItemResolution(existing.get(), false);
        }

        String nameEn = trimmed(payload.nameEn());
        String nameTh = trimmed(payload.nameTh());
        if (nameEn == null || nameTh == null) {
            throw ApiCodeException.badRequest("ITEM_FIELDS_REQUIRED",
                    "ยังไม่มีเกมรหัส " + itemId + " ในระบบ — ต้องส่ง item.nameEn และ item.nameTh มาให้สร้าง",
                    "item");
        }

        Item item = new Item();
        item.setItemId(itemId);
        item.setItemNameEn(nameEn);
        item.setItemNameTh(nameTh);
        item.setItemStatus("ACTIVE");
        item.setItemImageUrl(trimmed(payload.imageUrl()) != null ? trimmed(payload.imageUrl()) : "");
        item.setPlayerCountMin(payload.playerCountMin());
        item.setPlayerCountMax(payload.playerCountMax());
        item.setPlayTimeMin(payload.playTimeMin());
        item.setPlayTimeMax(payload.playTimeMax());
        item.setDifficultyRating(payload.difficultyRating());
        item.setDefaultPin(trimmed(payload.defaultPin()));
        Item saved = itemRepository.save(item);
        log.info("[GAME_COPY] Auto-created item {} ({}) from shop registration", itemId, nameEn);
        return new ItemResolution(saved, true);
    }

    /**
     * หาตู้ให้กล่อง — แอปส่งมา ใช้ตัวนั้น · ไม่ส่งมาและร้านผูกตู้เดียว ใช้ตู้นั้นให้เลย
     * ผูกหลายตู้หรือยังไม่ผูกเลย เก็บไว้แบบยังไม่มีที่อยู่ ให้คนมาระบุทีหลัง
     */
    private Vault resolvePlacement(Agent shop, String requestedVaultId) {
        String vaultId = trimmed(requestedVaultId);
        if (vaultId != null) {
            return requireVaultOfShop(shop, vaultId);
        }

        List<Binding> bindings = bindingRepository.findActiveByAgentId(shop.getAgentId());
        if (bindings.size() == 1) {
            return bindings.get(0).getVault();
        }
        return null;
    }

    private Vault requireVaultOfShop(Agent shop, String vaultId) {
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
                .orElseThrow(() -> ApiCodeException.notFound("VAULT_NOT_FOUND",
                        "ไม่พบตู้รหัส " + vaultId, "vaultId"));
        // กันร้านหนึ่งยัดของเข้าตู้ของอีกร้าน — ทุกคนใช้ x-auth-code ตัวเดียวกัน จึงกันที่ชั้นนี้
        bindingRepository.findActiveByAgentAndVault(shop.getAgentId(), vaultId)
                .orElseThrow(() -> ApiCodeException.conflict("VAULT_NOT_BOUND_TO_SHOP",
                        "ตู้ " + vaultId + " ไม่ได้ผูกกับร้าน " + shop.getAgentId(), "vaultId"));
        return vault;
    }

    private GameCopyResponse toResponse(VaultItem vi, boolean itemCreated) {
        Vault vault = vi.getVault();
        boolean inVault = vault != null;
        Item item = vi.getItem();
        return new GameCopyResponse(
                vi.getSerialNumber(),
                vi.getRfidTag(),
                vi.ownerAgentId(),
                inVault ? com.vault.dto.VaultItemResponse.IN_VAULT : com.vault.dto.VaultItemResponse.UNASSIGNED,
                inVault ? vault.getVaultId() : null,
                inVault ? vault.getVaultName() : null,
                // จองได้ต้องครบสามอย่าง: อยู่ในตู้ มีแท็ก และมี serial
                inVault && vi.getRfidTag() != null && vi.getSerialNumber() != null
                        && "ACTIVE".equals(vi.getStatus()),
                new GameCopyResponse.ItemRef(item.getItemId(), item.getItemNameEn(), item.getItemNameTh(), itemCreated),
                vi.getCreatedAt() != null ? vi.getCreatedAt().atZone(BANGKOK).toOffsetDateTime() : null
        );
    }

    private static String trimmed(String s) {
        return s != null && !s.isBlank() ? s.trim() : null;
    }
}
