package io.casehub.claudony.casehub.fleet;

/** Strategy for selecting which session to evict when the pool is at capacity. */
public enum EvictionStrategy {
    /** Evicts the session with the highest combined idle-time × memory-weight score. */
    MEMORY_WEIGHTED,
    /** Evicts the least recently used session regardless of memory consumption. */
    LRU
}
