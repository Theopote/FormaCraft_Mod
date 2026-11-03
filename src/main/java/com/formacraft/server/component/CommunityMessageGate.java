package com.formacraft.server.component;

import java.util.*;
import java.util.function.LongSupplier;

/** Network-thread admission before scheduling onto the game thread. */
public final class CommunityMessageGate {
    public static final int MAX_OWNERS = 64, MAX_PENDING = 64, PER_OWNER_PENDING = 2, PER_SECOND = 40;
    private static final class Counter { long window,touched; int messages,pending; Counter(long now) { window = touched = now; } }
    private final Map<UUID,Counter> owners = new HashMap<>();
    private final LongSupplier clock;
    private int pending;
    private boolean closed;
    public CommunityMessageGate(LongSupplier clock) { this.clock = clock; }
    public synchronized Permit acquire(UUID owner) {
        long now = clock.getAsLong();
        owners.values().removeIf(counter -> counter.pending == 0 && now-counter.touched >= 120_000_000_000L);
        if (closed || owner == null || pending >= MAX_PENDING) return null;
        var counter = owners.get(owner);
        if (counter == null) { if (owners.size() >= MAX_OWNERS) return null; counter = new Counter(now); owners.put(owner,counter); }
        if (now-counter.window >= 1_000_000_000L) { counter.window = now; counter.messages = 0; }
        if (counter.messages >= PER_SECOND || counter.pending >= PER_OWNER_PENDING) return null;
        counter.messages++; counter.pending++; counter.touched = now; pending++; return new Permit(counter);
    }
    public final class Permit implements AutoCloseable {
        private final Counter counter; private boolean released;
        private Permit(Counter counter) { this.counter = counter; }
        public void close() { synchronized (CommunityMessageGate.this) { if (!released) { released = true; counter.pending--; pending--; } } }
    }
    public synchronized void disconnect(UUID owner) { var counter = owners.get(owner); if (counter != null && counter.pending == 0) owners.remove(owner); }
    public synchronized void close() { closed = true; owners.clear(); }
}
