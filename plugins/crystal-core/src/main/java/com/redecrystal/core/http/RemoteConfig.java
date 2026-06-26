package com.redecrystal.core.http;

import java.util.Map;

/**
 * A configuration entry as served by the Config Service (mirror of the backend's
 * ConfigResponse). {@code config} is the free-form JSON payload.
 */
public record RemoteConfig(String key, int version, Map<String, Object> config) {

    public Object value(String field) {
        return config == null ? null : config.get(field);
    }

    public String string(String field, String fallback) {
        Object v = value(field);
        return v == null ? fallback : String.valueOf(v);
    }

    public int integer(String field, int fallback) {
        Object v = value(field);
        return (v instanceof Number n) ? n.intValue() : fallback;
    }

    public boolean bool(String field, boolean fallback) {
        Object v = value(field);
        return (v instanceof Boolean b) ? b : fallback;
    }
}
