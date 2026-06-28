package com.redecrystal.history.api;

import com.redecrystal.history.application.HistoryService;
import com.redecrystal.history.domain.ActivityEntity;
import com.redecrystal.history.domain.ChatMessageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** History API: record chat messages + activity, and read a player's trail. */
@RestController
public class HistoryController {

    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    public record MessageRequest(UUID uuid, String username, String server,
                                 String scope, String target, String message) {}

    public record ActivityRequest(UUID uuid, String username, String type, String detail, String server) {}

    public record MessageView(String username, String server, String scope, String target,
                              String message, String createdAt) {
        static MessageView from(ChatMessageEntity m) {
            return new MessageView(m.getUsername(), m.getServer(), m.getScope(), m.getTarget(),
                    m.getMessage(), m.getCreatedAt().toString());
        }
    }

    public record ActivityView(String username, String type, String detail, String server, String createdAt) {
        static ActivityView from(ActivityEntity a) {
            return new ActivityView(a.getUsername(), a.getType(), a.getDetail(), a.getServer(),
                    a.getCreatedAt().toString());
        }
    }

    @PostMapping("/api/chat/messages")
    public ResponseEntity<Void> recordMessage(@RequestBody MessageRequest body) {
        history.recordMessage(body.uuid(), body.username(), body.server(),
                body.scope(), body.target(), body.message());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/activity")
    public ResponseEntity<Void> logActivity(@RequestBody ActivityRequest body) {
        history.logActivity(body.uuid(), body.username(), body.type(), body.detail(), body.server());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/chat/messages")
    public List<MessageView> messages(@RequestParam UUID uuid,
                                      @RequestParam(defaultValue = "50") int limit) {
        return history.recentMessages(uuid, limit).stream().map(MessageView::from).toList();
    }

    @GetMapping("/api/profile/{uuid}/activity")
    public List<ActivityView> activity(@PathVariable UUID uuid,
                                       @RequestParam(defaultValue = "50") int limit) {
        return history.recentActivity(uuid, limit).stream().map(ActivityView::from).toList();
    }
}
