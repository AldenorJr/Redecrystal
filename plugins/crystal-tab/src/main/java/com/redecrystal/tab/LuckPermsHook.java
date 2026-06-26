package com.redecrystal.tab;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/**
 * Thin wrapper over the LuckPerms API for reading a player's prefix/suffix/group.
 * Null-safe: returns empty strings when LuckPerms or the user/meta is missing.
 */
public final class LuckPermsHook {

    private final LuckPerms luckPerms;

    public LuckPermsHook() {
        this.luckPerms = LuckPermsProvider.get();
    }

    public String prefix(Player player) {
        CachedMetaData meta = metaOf(player);
        return meta == null || meta.getPrefix() == null ? "" : meta.getPrefix();
    }

    public String suffix(Player player) {
        CachedMetaData meta = metaOf(player);
        return meta == null || meta.getSuffix() == null ? "" : meta.getSuffix();
    }

    public String primaryGroup(Player player) {
        CachedMetaData meta = metaOf(player);
        return meta == null ? "" : meta.getPrimaryGroup();
    }

    private CachedMetaData metaOf(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        return user == null ? null : user.getCachedData().getMetaData();
    }
}
