package com.redecrystal.network.api;

import com.redecrystal.network.api.NetworkDtos.HeartbeatRequest;
import com.redecrystal.network.api.NetworkDtos.RegisterServerRequest;
import com.redecrystal.network.api.NetworkDtos.ServerResponse;
import com.redecrystal.network.application.NetworkService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service Discovery API. Game servers self-register here on boot, send periodic
 * heartbeats, and deregister on shutdown. The proxy queries it to balance players.
 */
@RestController
@RequestMapping("/api/network")
public class NetworkController {

    private final NetworkService networkService;

    public NetworkController(NetworkService networkService) {
        this.networkService = networkService;
    }

    @GetMapping
    public List<ServerResponse> list(@RequestParam(required = false) String type) {
        return networkService.list(type).stream().map(ServerResponse::from).toList();
    }

    @GetMapping("/{serverId}")
    public ServerResponse get(@PathVariable String serverId) {
        return ServerResponse.from(networkService.get(serverId));
    }

    @PostMapping("/register")
    public ServerResponse register(@Valid @RequestBody RegisterServerRequest req) {
        return ServerResponse.from(networkService.register(
                req.serverId(), req.type(), req.host(), req.port(), req.maxPlayers()));
    }

    @PostMapping("/{serverId}/heartbeat")
    public ServerResponse heartbeat(@PathVariable String serverId,
                                    @Valid @RequestBody HeartbeatRequest req) {
        return ServerResponse.from(
                networkService.heartbeat(serverId, req.onlinePlayers(), req.status(),
                        req.tps(), req.memoryMb(), req.cpuLoad()));
    }

    @DeleteMapping("/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deregister(@PathVariable String serverId) {
        networkService.deregister(serverId);
    }
}
