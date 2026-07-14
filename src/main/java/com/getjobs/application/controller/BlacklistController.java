package com.getjobs.application.controller;

import com.getjobs.application.entity.BlacklistEntity;
import com.getjobs.application.service.BlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局公司黑名单 API
 */
@Slf4j
@RestController
@RequestMapping("/api/blacklist")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BlacklistController {

    private final BlacklistService blacklistService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "type", required = false) String type) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<BlacklistEntity> data = (type == null || type.isBlank())
                    ? blacklistService.listAll()
                    : blacklistService.listByType(type.trim());
            response.put("success", true);
            response.put("data", data);
            response.put("message", "获取黑名单成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取黑名单失败", e);
            response.put("success", false);
            response.put("message", "获取黑名单失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String type = body != null ? body.get("type") : null;
            String value = body != null ? body.get("value") : null;
            if (type == null || type.isBlank()) {
                type = BlacklistEntity.TYPE_COMPANY;
            }
            if (!BlacklistEntity.TYPE_COMPANY.equalsIgnoreCase(type.trim())) {
                response.put("success", false);
                response.put("message", "暂仅支持 type=company");
                return ResponseEntity.badRequest().body(response);
            }
            BlacklistEntity entity = blacklistService.addCompany(value);
            response.put("success", true);
            response.put("data", entity);
            response.put("message", "添加黑名单成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("添加黑名单失败", e);
            response.put("success", false);
            response.put("message", "添加黑名单失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean ok = blacklistService.deleteById(id);
            if (ok) {
                response.put("success", true);
                response.put("message", "删除成功");
                return ResponseEntity.ok(response);
            }
            response.put("success", false);
            response.put("message", "记录不存在");
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("删除黑名单失败: {}", id, e);
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
