package com.redecrystal.history.application;

import com.redecrystal.history.domain.ActivityEntity;
import com.redecrystal.history.domain.ActivityRepository;
import com.redecrystal.history.domain.ChatMessageEntity;
import com.redecrystal.history.domain.ChatMessageRepository;
import com.redecrystal.profile.application.ProfileService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Player history: persists every chat message ({@code chat_messages}) and a
 * unified activity trail ({@code player_activity}). Recording a message also
 * writes a matching activity entry and bumps the profile's message counter.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final ChatMessageRepository messages;
    private final ActivityRepository activity;
    private final ProfileService profiles;

    public HistoryService(ChatMessageRepository messages, ActivityRepository activity, ProfileService profiles) {
        this.messages = messages;
        this.activity = activity;
        this.profiles = profiles;
    }

    /** Persist a chat message, mirror it into the activity trail, and bump the counter. */
    @Transactional
    public void recordMessage(UUID uuid, String username, String server,
                              String scope, String target, String message) {
        String sc = (scope == null || scope.isBlank()) ? "global" : scope;
        messages.save(new ChatMessageEntity(uuid, username, server, sc, target, message));

        String type = "tell".equalsIgnoreCase(sc) ? "TELL" : "CHAT";
        String detail = "tell".equalsIgnoreCase(sc) && target != null
                ? "-> " + target + ": " + message
                : message;
        activity.save(new ActivityEntity(uuid, username, type, detail, server));

        try {
            profiles.addMessages(uuid, 1);
        } catch (Exception e) {
            // Profile may not exist yet (message before first join sync) — don't fail the save.
            log.debug("messages_sent increment skipped for {}: {}", uuid, e.toString());
        }
    }

    /** Append a generic activity event (JOIN/QUIT/KILL/DEATH/COMMAND/...). */
    @Transactional
    public void logActivity(UUID uuid, String username, String type, String detail, String server) {
        activity.save(new ActivityEntity(uuid, username, type, detail, server));
    }

    @Transactional(readOnly = true)
    public List<ActivityEntity> recentActivity(UUID uuid, int limit) {
        return activity.findByPlayerUuidOrderByCreatedAtDesc(uuid, PageRequest.of(0, clamp(limit)));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageEntity> recentMessages(UUID uuid, int limit) {
        return messages.findByPlayerUuidOrderByCreatedAtDesc(uuid, PageRequest.of(0, clamp(limit)));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
