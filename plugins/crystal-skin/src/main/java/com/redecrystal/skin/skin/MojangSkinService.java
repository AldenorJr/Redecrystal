package com.redecrystal.skin.skin;

import com.fasterxml.jackson.databind.JsonNode;
import com.redecrystal.core.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Looks up a player's signed skin texture from the public Mojang APIs. Two hops:
 * name → UUID, then UUID → signed "textures" property. All calls block on I/O, so
 * callers MUST run {@link #fetch(String)} off the main thread.
 */
public final class MojangSkinService {

    private static final String NAME_TO_UUID = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String UUID_TO_PROFILE =
            "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String TEXTURES = "textures";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** Why a lookup failed, so callers can show the right player-facing message. */
    public enum Reason { NOT_FOUND, RATE_LIMITED, TRANSPORT }

    /** Tipped failure (never a raw RuntimeException) — backend errors must have a fallback. */
    public static final class SkinLookupException extends Exception {
        private final transient Reason reason;

        SkinLookupException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Resolve {@code name} to its current signed skin texture. Blocking — call async. */
    public SkinTexture fetch(String name, long appliedAt) throws SkinLookupException {
        JsonNode profile = get(NAME_TO_UUID + name);
        if (profile == null) {
            throw new SkinLookupException(Reason.NOT_FOUND, "Jogador não encontrado: " + name);
        }
        String uuid = profile.path("id").asText(null);
        String canonical = profile.path("name").asText(name);
        if (uuid == null || uuid.isEmpty()) {
            throw new SkinLookupException(Reason.NOT_FOUND, "Jogador não encontrado: " + name);
        }

        JsonNode signed = get(UUID_TO_PROFILE + uuid + "?unsigned=false");
        if (signed == null) {
            throw new SkinLookupException(Reason.NOT_FOUND, "Perfil indisponível: " + name);
        }
        for (JsonNode prop : signed.path("properties")) {
            if (TEXTURES.equals(prop.path("name").asText())) {
                String value = prop.path("value").asText(null);
                String signature = prop.path("signature").asText(null);
                if (value != null && !value.isEmpty()) {
                    return new SkinTexture(canonical, uuid, value, signature, appliedAt);
                }
            }
        }
        throw new SkinLookupException(Reason.NOT_FOUND, "Esse jogador não tem skin: " + name);
    }

    /** GET + parse JSON; {@code null} on 204/404 (absent), tipped on 429/transport. */
    private JsonNode get(String url) throws SkinLookupException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SkinLookupException(Reason.TRANSPORT, "Mojang indisponível: " + e.getMessage());
        }
        int code = resp.statusCode();
        if (code == 204 || code == 404) {
            return null;
        }
        if (code == 429) {
            throw new SkinLookupException(Reason.RATE_LIMITED, "Muitas requisições à Mojang.");
        }
        if (code < 200 || code >= 300 || resp.body() == null || resp.body().isEmpty()) {
            throw new SkinLookupException(Reason.TRANSPORT, "Mojang respondeu HTTP " + code);
        }
        try {
            return Json.MAPPER.readTree(resp.body());
        } catch (Exception e) {
            throw new SkinLookupException(Reason.TRANSPORT, "Resposta inválida da Mojang.");
        }
    }
}
