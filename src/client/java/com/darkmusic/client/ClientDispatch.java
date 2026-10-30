package com.darkmusic.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Hands the last step of an operation to the client thread, which owns the state that step changes. The operation's
 * future always ends: if the step throws, the future fails with it; if the client thread refuses the step (Minecraft
 * has stopped running tasks while closing), the future fails with that refusal and the client-owned state is left as
 * it is, since nothing may change it from another thread and the client is going away.
 */
final class ClientDispatch {
    private ClientDispatch() {
    }

    static void run(Executor client, CompletableFuture<?> finished, Runnable step) {
        try {
            client.execute(() -> {
                try {
                    step.run();
                } catch (RuntimeException | LinkageError unexpected) {
                    finished.completeExceptionally(unexpected);
                }
            });
        } catch (RuntimeException refused) {
            finished.completeExceptionally(refused);
        }
    }
}
