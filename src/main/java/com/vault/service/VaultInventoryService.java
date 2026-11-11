package com.vault.service;

import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.event.VaultInventoryChanged;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ส่งรายการ "แท็กไหนคือเกมอะไร" ของตู้หนึ่ง ไปให้ตู้ (สัญญา §3.7 cmd/inventory/sync)
 *
 * ทำไมต้องมี: ลงทะเบียนกล่องแล้วตู้ยังไม่รู้จักแท็ก · เอากล่องไปแตะ ตู้มองเป็นของแปลกปลอม
 * แตะผิดครบ 3 ครั้งตู้เข้า lockdown เอง แล้วออกเองไม่ได้ ต้องมีคนสั่งปลดจากหลังบ้าน
 *
 * ส่งทั้งชุดด้วย mode=replace ทุกครั้ง ไม่ส่งทีละแถว เพราะสัญญาไม่มีคำสั่งลบแท็กเดี่ยว
 * ถ้าใช้ upsert อย่างเดียว แท็กของกล่องที่ย้ายออกไปแล้วจะค้างในตู้ตลอดไป
 * — ส่งทั้งชุดจึงเป็นทั้งการเพิ่ม การลบ และการซ่อมตัวเองเมื่อตู้กับฐานข้อมูลหลุดกัน
 *
 * sync รอ commit ก่อนเสมอ ถ้าส่งก่อนแล้ว transaction ล้มทีหลัง ตู้จะถือรายการที่เราไม่มี
 */
@Service
public class VaultInventoryService {

    private static final Logger log = LoggerFactory.getLogger(VaultInventoryService.class);

    /**
     * ตู้รับแท็กสองแบบ — แถวเดียวผิด ตู้ปฏิเสธทั้ง batch จึงต้องกรองก่อนส่ง
     *
     * UHF — EPC 24 hex
     * NFC — UID 8–16 hex จำนวนคู่ (ปกติ 14) เช่นตู้ screenless ที่อ่าน NFC ไม่ใช่ UHF
     *
     * ความยาวไม่ทับกัน (NFC ไม่เกิน 16 · UHF เท่ากับ 24) จึงแยกชนิดจากรูปแบบได้เลย
     */
    private static final Pattern UHF_EPC = Pattern.compile("^[0-9A-Fa-f]{24}$");
    private static final Pattern NFC_UID = Pattern.compile("^(?:[0-9A-Fa-f]{2}){4,8}$");

    /**
     * ชนิดแท็กตามที่ตู้เข้าใจ — null ถ้าไม่เข้ารูปแบบไหนเลย (ไม่ส่งไปให้ตู้)
     *
     * package-private เพื่อให้เทสต์เรียกตรงได้ ไม่ต้องผ่าน MQTT
     */
    static String tagTypeOf(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (UHF_EPC.matcher(t).matches()) return "uhf";
        if (NFC_UID.matcher(t).matches()) return "nfc";
        return null;
    }

    private final VaultItemRepository vaultItemRepository;
    private final VaultRepository vaultRepository;
    private final AwsIotService awsIotService;

    public VaultInventoryService(VaultItemRepository vaultItemRepository,
                                 VaultRepository vaultRepository,
                                 AwsIotService awsIotService) {
        this.vaultItemRepository = vaultItemRepository;
        this.vaultRepository = vaultRepository;
        this.awsIotService = awsIotService;
    }

    /**
     * ผลการ sync หนึ่งครั้ง
     *
     * ตู้ไม่ตอบ ack มา ตัวเลขเหล่านี้คือ "เราส่งอะไรไป" ไม่ใช่ "ตู้รับไปแล้ว"
     * skippedNoTag คือกล่องที่ยังไม่ได้แปะแท็ก — ตู้ชี้กล่องพวกนี้ไม่ได้อยู่แล้ว
     */
    public record SyncResult(String vaultId, String thingName, int sent,
                             int skippedNoTag, int skippedBadTag, boolean published) {}

    /**
     * ยิงอัตโนมัติหลัง commit
     *
     * fallbackExecution=true เพื่อให้ยิงจากที่ที่ไม่มี transaction ได้ด้วย
     * ไม่งั้น event จะถูกทิ้งเงียบ ๆ แล้วตู้ไม่เคยได้รายการใหม่เลย
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInventoryChanged(VaultInventoryChanged event) {
        if (event.vaultId() == null) return;
        try {
            SyncResult r = syncVault(event.vaultId());
            log.info("[INVENTORY] Synced vault={} reason={} sent={} skipped={}",
                    r.vaultId(), event.reason(), r.sent(), r.skippedNoTag() + r.skippedBadTag());
        } catch (Exception e) {
            // งานหลัง commit แล้ว — ล้มที่นี่ต้องไม่ย้อนไปทำให้คำสั่งที่สำเร็จแล้วพัง
            // ของที่ตู้ยังไม่รู้ ซ่อมได้ด้วยปุ่ม sync ที่หน้า vault
            log.error("[INVENTORY] Sync failed vault={} reason={}: {}",
                    event.vaultId(), event.reason(), e.getMessage(), e);
        }
    }

    /** ส่งรายการแท็กทั้งชุดของตู้นี้ไปให้ตู้ — เรียกจากปุ่มที่หน้า vault ได้ด้วย */
    @Transactional(readOnly = true)
    public SyncResult syncVault(String vaultId) {
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
                .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));

        List<Map<String, String>> mappings = new ArrayList<>();
        int noTag = 0;
        int badTag = 0;

        for (VaultItem vi : vaultItemRepository.findByVaultId(vaultId)) {
            if (!"ACTIVE".equals(vi.getStatus())) continue;

            String epc = vi.getRfidTag();
            if (epc == null || epc.isBlank()) {
                noTag++;
                continue;
            }
            String tagType = tagTypeOf(epc);
            if (tagType == null) {
                // ข้อมูลเก่าที่เข้ามาก่อนจะมีการตรวจรูปแบบ — ส่งไปจะทำให้ตู้ตีกลับทั้งชุด
                log.warn("[INVENTORY] Skipping copy serial={} — tag is neither UHF 24 hex nor NFC 8–16 hex: {}",
                        vi.getSerialNumber(), epc);
                badTag++;
                continue;
            }

            Item item = vi.getItem();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("epc", epc.trim().toUpperCase());
            // UHF ไม่ส่ง tag_type — payload เดิมที่ตู้รับอยู่แล้ว เปลี่ยนไปอาจพังของที่ใช้งานได้
            if ("nfc".equals(tagType)) {
                row.put("tag_type", "nfc");
            }
            row.put("game_id", item.getItemId());
            // ตู้ต้องการ game_title ที่ไม่ว่าง — ของที่ไม่มีชื่ออังกฤษใช้รหัสเกมไปก่อน
            row.put("game_title", item.getItemNameEn() != null && !item.getItemNameEn().isBlank()
                    ? item.getItemNameEn() : item.getItemId());
            mappings.add(row);
        }

        String thingName = vault.effectiveThingName();
        awsIotService.publishInventorySync(thingName, UUID.randomUUID().toString(), "replace", mappings);

        return new SyncResult(vaultId, thingName, mappings.size(), noTag, badTag, awsIotService.isConfigured());
    }

    /** รายการที่ "จะ" ส่ง — ใช้โชว์ให้คนดูก่อนกดส่ง หรือตรวจว่าตู้ควรรู้จักอะไรบ้าง */
    @Transactional(readOnly = true)
    public Optional<Integer> countSyncable(String vaultId) {
        if (vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId).isEmpty()) return Optional.empty();
        long n = vaultItemRepository.findByVaultId(vaultId).stream()
                .filter(vi -> "ACTIVE".equals(vi.getStatus()))
                .filter(vi -> tagTypeOf(vi.getRfidTag()) != null)
                .count();
        return Optional.of((int) n);
    }
}
