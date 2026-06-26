package com.redecrystal.parkour.api;

import com.redecrystal.parkour.application.ParkourService;
import com.redecrystal.parkour.application.ParkourService.Entry;
import com.redecrystal.parkour.application.ParkourService.Result;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Parkour leaderboard API. */
@RestController
@RequestMapping("/api/parkour")
public class ParkourController {

    private final ParkourService parkourService;

    public ParkourController(ParkourService parkourService) {
        this.parkourService = parkourService;
    }

    public record SubmitRequest(
            @NotNull UUID uuid,
            String username,
            @Min(1) long timeMs) {}

    @PostMapping("/time")
    public Result submit(@RequestBody SubmitRequest req) {
        return parkourService.submit(req.uuid(), req.username(), req.timeMs());
    }

    @GetMapping("/top")
    public List<Entry> top(@RequestParam(defaultValue = "10") int limit) {
        return parkourService.top(Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/best/{uuid}")
    public Map<String, Object> best(@PathVariable UUID uuid) {
        return Map.of("uuid", uuid.toString(), "bestTimeMs", parkourService.best(uuid));
    }
}
