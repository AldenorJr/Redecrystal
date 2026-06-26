package com.redecrystal.core.http;

/** A stored inventory snapshot: Base64 content + optimistic-lock version. */
public record InventoryData(String content, int version) {

    public boolean isEmpty() {
        return content == null || content.isEmpty();
    }
}
