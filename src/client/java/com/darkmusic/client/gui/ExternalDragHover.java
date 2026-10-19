package com.darkmusic.client.gui;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.POINT;
import org.lwjgl.system.windows.User32;

/**
 * A read-only Windows drag hint. File acceptance still belongs to Minecraft's drop callback.
 */
final class ExternalDragHover {
    private static boolean unavailable;

    static boolean over(long window) {
        if (unavailable || Platform.get() != Platform.WINDOWS) return false;
        try {
            // External drags can activate the target window while the source still owns the mouse.
            // GLFW's button state distinguishes those drags from clicks started inside Minecraft.
            if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_RELEASE
                || GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_RELEASE) return false;
            boolean held = User32.GetAsyncKeyState(1) < 0 || User32.GetAsyncKeyState(2) < 0;
            if (!held) return false;
            // The drag source can own mouse capture, leaving GLFW's hover flag stale.
            // Query the cursor and client area in the same native screen coordinate system.
            try (var stack = MemoryStack.stackPush()) {
                var cursor = POINT.malloc(stack);
                var origin = POINT.calloc(stack);
                var width = stack.mallocInt(1);
                var height = stack.mallocInt(1);
                if (!User32.GetCursorPos(cursor) || !User32.ClientToScreen(GLFWNativeWin32.glfwGetWin32Window(window), origin))
                    return false;
                GLFW.glfwGetWindowSize(window, width, height);
                return cursor.x() >= origin.x() && cursor.y() >= origin.y()
                    && cursor.x() < origin.x() + width.get(0) && cursor.y() < origin.y() + height.get(0);
            }
        } catch (RuntimeException | LinkageError e) {
            unavailable = true;
            org.slf4j.LoggerFactory.getLogger(ExternalDragHover.class).debug("Native drag hint unavailable", e);
            return false;
        }
    }
}
