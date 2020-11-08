package com.netease.nim.camellia.redis.proxy.command.async.hotkey;

/**
 *
 * Created by caojiajun on 2020/10/22
 */
public class HotKeyConfig {
    private final long checkMillis;
    private final long checkCacheMaxCapacity;
    private final long checkThreshold;
    private final int maxHotKeyCount;

    public HotKeyConfig(long checkMillis, long checkCacheMaxCapacity, long checkThreshold, int maxHotKeyCount) {
        this.checkMillis = checkMillis;
        this.checkCacheMaxCapacity = checkCacheMaxCapacity;
        this.checkThreshold = checkThreshold;
        this.maxHotKeyCount = maxHotKeyCount;
    }

    public long getCheckMillis() {
        return checkMillis;
    }

    public long getCheckCacheMaxCapacity() {
        return checkCacheMaxCapacity;
    }

    public long getCheckThreshold() {
        return checkThreshold;
    }

    public int getMaxHotKeyCount() {
        return maxHotKeyCount;
    }
}
