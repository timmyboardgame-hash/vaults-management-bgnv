package com.vault.controller;

import com.vault.dto.BatchRegisterGameCopyRequest;
import com.vault.dto.BatchRegisterGameCopyResponse;
import com.vault.dto.GameCopyResponse;
import com.vault.dto.RegisterGameCopyRequest;
import com.vault.dto.UpdateGameCopyRequest;
import com.vault.exception.ApiCodeException;
import com.vault.service.GameCopyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ลงทะเบียนกล่องเกมจากแอป/เว็บของร้าน
 *
 * auth ใช้ x-auth-code เหมือน API เดิมทั้งหมด (ดู SecurityConfig + ApiKeyAuthFilter)
 * error ตอบเป็น {code, message, field} — แอปเขียนเงื่อนไขจาก code
 */
@RestController
@RequestMapping("/api/v1")
public class GameCopyController {

    private final GameCopyService gameCopyService;

    public GameCopyController(GameCopyService gameCopyService) {
        this.gameCopyService = gameCopyService;
    }

    /** 201 = กล่องใหม่ · 200 = ยิงซ้ำด้วยข้อมูลเดิม คืนกล่องใบเดิม ไม่สร้างซ้ำ */
    @PostMapping("/shops/{shopId}/game-copies")
    public ResponseEntity<GameCopyResponse> register(@PathVariable String shopId,
                                                     @RequestBody RegisterGameCopyRequest req) {
        if (req == null) {
            throw ApiCodeException.badRequest("SERIAL_REQUIRED", "ไม่มี body มาด้วย", "serialNumber");
        }
        GameCopyService.Registration result = gameCopyService.register(shopId, req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.copy());
    }

    @PostMapping("/shops/{shopId}/game-copies/batch")
    public ResponseEntity<BatchRegisterGameCopyResponse> registerBatch(
            @PathVariable String shopId,
            @RequestBody BatchRegisterGameCopyRequest req) {
        return ResponseEntity.ok(gameCopyService.registerBatch(shopId, req));
    }

    /** กล่องของร้านตัวเอง · กรองได้ด้วย ?placement=UNASSIGNED หรือ ?itemId= */
    @GetMapping("/shops/{shopId}/game-copies")
    public ResponseEntity<List<GameCopyResponse>> listByShop(
            @PathVariable String shopId,
            @RequestParam(required = false) String placement,
            @RequestParam(required = false) String itemId) {
        return ResponseEntity.ok(gameCopyService.listByShop(shopId, placement, itemId));
    }

    @GetMapping("/game-copies/{serialNumber}")
    public ResponseEntity<GameCopyResponse> get(@PathVariable String serialNumber) {
        return gameCopyService.getBySerial(serialNumber)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> ApiCodeException.notFound("COPY_NOT_FOUND",
                        "ไม่พบกล่อง serial " + serialNumber, "serialNumber"));
    }

    /** ใส่ตู้ให้กล่องที่ยังไม่มีที่อยู่ หรือเปลี่ยนแท็กที่เสีย · เปลี่ยน serial ไม่ได้ */
    @PatchMapping("/game-copies/{serialNumber}")
    public ResponseEntity<GameCopyResponse> update(@PathVariable String serialNumber,
                                                   @RequestBody UpdateGameCopyRequest req) {
        if (req == null || (req.vaultId() == null && req.rfidTag() == null)) {
            throw ApiCodeException.badRequest("NOTHING_TO_UPDATE",
                    "ต้องส่ง vaultId หรือ rfidTag มาอย่างน้อยหนึ่งอย่าง", null);
        }
        return ResponseEntity.ok(gameCopyService.update(serialNumber, req));
    }

    /** soft delete — ประวัติการยืมยังอยู่ครบ */
    @DeleteMapping("/game-copies/{serialNumber}")
    public ResponseEntity<Void> delete(@PathVariable String serialNumber) {
        gameCopyService.delete(serialNumber);
        return ResponseEntity.noContent().build();
    }
}
