package com.darkmusic.client.testing;

import java.util.Arrays;
import java.util.Locale;

/**
 * Opt-in only; observations, never timing thresholds. No other build or native smoke should run beside it.
 */
public final class HotCost {
    private static volatile long consumed;

    private HotCost() {
    }

    @FunctionalInterface
    public interface Task {
        long run() throws Exception;
    }

    public static void measure(String method, Task task) throws Exception {
        long value = 0;
        for (int i = 0; i < 10_000; i++) value += task.run();
        consumed = value;
        double[] rounds = new double[15];
        for (int round = 0; round < rounds.length; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) value += task.run();
            rounds[round] = (System.nanoTime() - start) / 1000.0;
            consumed = value;
        }
        System.out.printf(Locale.ROOT, "HOT_ROUNDS %s %s%n", method, Arrays.toString(rounds));
        Arrays.sort(rounds);
        System.out.printf(Locale.ROOT, "HOT_COST %s median=%.3f ns/op min=%.3f max=%.3f p10=%.3f p90=%.3f spread=%.3f%n",
            method, rounds[7], rounds[0], rounds[14], rounds[1], rounds[13], rounds[13] - rounds[1]);
    }
}
