package com.darkmusic.client;

import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Cleanup steps that must all be attempted. A step that throws is logged and recorded, and the steps after it still
 * run, so one failed close cannot strand unrelated resources. The steps run once; running again does nothing.
 * Not thread-safe: the owner adds and runs steps on one thread.
 */
final class Cleanup {
    private record Step(String name, Runnable action) {
    }

    private final Deque<Step> steps = new ArrayDeque<>();
    private boolean ran;

    /**
     * Adds a step after those already added.
     */
    Cleanup then(String name, Runnable action) {
        steps.addLast(new Step(name, action));
        return this;
    }

    /**
     * Adds a step before those already added, so resources acquired one after another are released in reverse.
     */
    Cleanup first(String name, Runnable action) {
        steps.addFirst(new Step(name, action));
        return this;
    }

    /**
     * Runs every step in order and returns the failures, each named after its step.
     */
    List<RuntimeException> run() {
        if (ran) return List.of();
        ran = true;
        var failures = new ArrayList<RuntimeException>();
        for (var step : steps) {
            try {
                step.action().run();
            } catch (RuntimeException | LinkageError failure) {
                LoggerFactory.getLogger("darkmusic").warn("Cleanup step failed: {}", step.name(), failure);
                failures.add(new IllegalStateException(step.name() + " failed", failure));
            }
        }
        steps.clear();
        return List.copyOf(failures);
    }
}
