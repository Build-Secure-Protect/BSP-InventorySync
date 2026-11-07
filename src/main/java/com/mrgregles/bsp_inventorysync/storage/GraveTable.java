package com.mrgregles.bsp_inventorysync.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Graves in memory, under one lock: the reference {@link GraveStore} rules, shared by {@link MemoryStore} and
 * the world's own grave data. Plain values, no futures: the callers wrap them.
 */
public final class GraveTable {
    private final Map<UUID, Grave> graves = new LinkedHashMap<>();

    public synchronized boolean add(Grave grave) {
        return graves.putIfAbsent(grave.id(), grave) == null;
    }

    public synchronized Optional<Grave> get(UUID id) {
        return Optional.ofNullable(graves.get(id));
    }

    public synchronized List<Grave> of(UUID owner) {
        List<Grave> out = new ArrayList<>();
        for (Grave g : graves.values()) {
            if (g.owner().equals(owner)) {
                out.add(g);
            }
        }
        out.sort(Comparator.comparingLong(Grave::diedAt).reversed());
        return out;
    }

    public synchronized boolean update(Grave grave) {
        Grave now = graves.get(grave.id());
        if (now == null || now.revision() != grave.revision()) {
            return false;
        }
        graves.put(grave.id(), grave.nextRevision());
        return true;
    }

    public synchronized boolean delete(UUID id, long revision) {
        Grave now = graves.get(id);
        if (now == null || now.revision() != revision) {
            return false;
        }
        graves.remove(id);
        return true;
    }

    public synchronized List<Grave> touchedBefore(long before, int limit) {
        return graves.values().stream().filter(g -> g.touchedAt() < before)
                .sorted(Comparator.comparingLong(Grave::touchedAt)).limit(Math.max(0, limit)).toList();
    }

    public synchronized Collection<Grave> all() {
        return List.copyOf(graves.values());
    }

    public synchronized int size() {
        return graves.size();
    }

    /** Replaces everything (loading the world's data). */
    public synchronized void replaceAll(Collection<Grave> loaded) {
        graves.clear();
        for (Grave g : loaded) {
            graves.put(g.id(), g);
        }
    }
}
