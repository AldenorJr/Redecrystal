package com.redecrystal.profile.api;

import com.redecrystal.profile.domain.ProfileEntity;
import com.redecrystal.profile.application.ProfileService;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile API: fetch, ensure-exists, and apply additive stat changes. */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    public record ProfileResponse(String uuid, String username, String rank, int level,
                                  long experience, long coins, long playSeconds) {
        static ProfileResponse from(ProfileEntity p) {
            return new ProfileResponse(p.getPlayerUuid().toString(), p.getUsername(), p.getRank(),
                    p.getLevel(), p.getExperience(), p.getCoins(), p.getPlaySeconds());
        }
    }

    public record EnsureRequest(String username) {}

    public record AddStatsRequest(long coins, long experience, long playSeconds) {}

    @GetMapping("/{uuid}")
    public ProfileResponse get(@PathVariable UUID uuid) {
        return ProfileResponse.from(profileService.get(uuid));
    }

    @PutMapping("/{uuid}")
    public ProfileResponse ensure(@PathVariable UUID uuid, @RequestBody @NotNull EnsureRequest body) {
        return ProfileResponse.from(profileService.ensure(uuid, body.username()));
    }

    @PostMapping("/{uuid}/add")
    public ProfileResponse addStats(@PathVariable UUID uuid, @RequestBody AddStatsRequest body) {
        return ProfileResponse.from(
                profileService.addStats(uuid, body.coins(), body.experience(), body.playSeconds()));
    }
}
