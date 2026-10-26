package com.darkmusic.client.audio;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client/worker synchronized, bounded failure history; file identity changes get a fresh budget.
 */
final class WaveformRetries {
    private record Failure(int attempts, long after) {
    }

    private final LinkedHashMap<String, Failure> failures = new LinkedHashMap<>();

    synchronized boolean ready(String identity, long now) {
        var f = failures.get(identity);
        return f == null || f.after != Long.MAX_VALUE && now >= f.after;
    }

    synchronized void failed(String identity, long now, boolean permanent) {
        var old = failures.get(identity);
        int attempts = old == null ? 1 : Math.min(7, old.attempts + 1);
        long wait = TimeUnit.MINUTES.toNanos(Math.min(60, 1L << (attempts - 1)));
        failures.put(identity, new Failure(attempts, permanent ? Long.MAX_VALUE : now + wait));
        while (failures.size() > 128) failures.remove(failures.keySet().iterator().next());
    }

    synchronized void succeeded(String identity) {
        failures.remove(identity);
    }
}
