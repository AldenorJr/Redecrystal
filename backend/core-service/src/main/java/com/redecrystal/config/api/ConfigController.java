package com.redecrystal.config.api;

import com.redecrystal.config.api.ConfigDtos.ConfigResponse;
import com.redecrystal.config.api.ConfigDtos.UpdateConfigRequest;
import com.redecrystal.config.application.ConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Config Service API. Plugins fetch their config here on bootstrap and never
 * store it locally; updates trigger a {@code config-updated} event for hot reload.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public List<ConfigResponse> list() {
        return configService.findAll().stream().map(ConfigResponse::from).toList();
    }

    @GetMapping("/{key}")
    public ConfigResponse get(@PathVariable String key) {
        // getEntity gives version + timestamp; get() is the cache-first hot path.
        return ConfigResponse.from(configService.getEntity(key));
    }

    @PutMapping("/{key}")
    public ConfigResponse upsert(@PathVariable String key,
                                 @Valid @RequestBody UpdateConfigRequest request) {
        return ConfigResponse.from(configService.upsert(key, request.config()));
    }
}
