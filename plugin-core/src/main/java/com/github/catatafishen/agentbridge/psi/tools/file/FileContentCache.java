package com.github.catatafishen.agentbridge.psi.tools.file;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A simple thread-safe LRU cache for file contents.
 */
public class FileContentCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    public FileContentCache(int capacity) {
        super(capacity, 0.75f, true);
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;
    }

    // Must be used with external synchronization if thread-safety is required,
    // or wrapped in a ConcurrentMap.
    public static <K, V> Map<K, V> synchronizedCache(int capacity) {
        return java.util.Collections.synchronizedMap(new FileContentCache<>(capacity));
    }
}
