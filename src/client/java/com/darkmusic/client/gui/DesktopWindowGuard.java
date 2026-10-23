package com.darkmusic.client.gui;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Keep invisible desktop helpers from auto-minimizing an exclusive fullscreen window.
 */
public final class DesktopWindowGuard implements AutoCloseable {
    private static final java.util.Map<Long, Lease> ACTIVE = new java.util.HashMap<>();
    private final Minecraft client;
    private final long handle;
    private final Lease lease;
    private boolean closed;

    /**
     * Construct and close only on the Minecraft thread. Never changes fullscreen or focus.
     */
    public DesktopWindowGuard() {
        client = Minecraft.getInstance();
        handle = client.getWindow().handle();
        lease = ACTIVE.computeIfAbsent(handle, window -> new Lease(GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_AUTO_ICONIFY)));
        lease.users++;
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Let queued native focus messages settle after the helper exits. Overlapping
        // requests retain the original attribute until the last request releases it.
        java.util.concurrent.CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> client.execute(() -> {
            if (--lease.users == 0) {
                ACTIVE.remove(handle, lease);
                if (client.getWindow().handle() == handle)
                    GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, lease.autoIconify);
            }
        }));
    }

    private static final class Lease {
        private final int autoIconify;
        private int users;

        private Lease(int autoIconify) {
            this.autoIconify = autoIconify;
        }
    }
}
