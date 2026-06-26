package com.redecrystal.inventory.api;

import com.redecrystal.inventory.application.InventoryService;
import com.redecrystal.inventory.application.InventoryService.Snapshot;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inventory API: load a player's inventory and save it with optimistic locking. */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    public record InventoryResponse(String content, int version) {}

    public record SaveRequest(String content, int version) {}

    @GetMapping("/{uuid}/{serverType}")
    public InventoryResponse get(@PathVariable UUID uuid, @PathVariable String serverType) {
        Snapshot s = inventoryService.get(uuid, serverType);
        return new InventoryResponse(s.content(), s.version());
    }

    @PutMapping("/{uuid}/{serverType}")
    public InventoryResponse save(@PathVariable UUID uuid, @PathVariable String serverType,
                                  @RequestBody SaveRequest body) {
        int newVersion = inventoryService.save(uuid, serverType, body.content(), body.version());
        return new InventoryResponse(body.content(), newVersion);
    }
}
