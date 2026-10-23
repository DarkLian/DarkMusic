package com.darkmusic.smoke;

import com.darkmusic.client.gui.DesktopWindowGuard;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Native window checks: flag-only fullscreen assertions miss minimized and oversized windows.
 */
public final class DesktopWindowSmoke {
    private int phase, ticks, originalAutoIconify;
    private DesktopWindowGuard first, second;

    public boolean tick(Minecraft client) {
        var window = client.getWindow();
        switch (phase) {
            case 0 -> {
                require(window.isFullscreen(), "Fullscreen must be configured before window checks");
                originalAutoIconify = GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_AUTO_ICONIFY);
                first = new DesktopWindowGuard();
                second = new DesktopWindowGuard();
                first.close();
                first.close();
                phase++;
                ticks = 0;
            }
            case 1 -> {
                if (++ticks < 8) return false;
                require(GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_AUTO_ICONIFY) == GLFW.GLFW_FALSE,
                    "An overlapping desktop request restored auto-iconify too early");
                fullscreenKey(client);
                phase++;
                ticks = 0;
            }
            case 2 -> {
                if (++ticks < 8) return false;
                require(!window.isFullscreen() && GLFW.glfwGetWindowMonitor(window.handle()) == 0,
                    "F11 cannot exit fullscreen during a desktop request");
                require(window.getWidth() < 1920 && window.getHeight() < 1080,
                    "Windowed fallback still covers the entire fullscreen display");
                second.close();
                phase++;
                ticks = 0;
            }
            case 3 -> {
                if (++ticks < 8) return false;
                require(!window.isFullscreen(), "Desktop completion undid the user's fullscreen choice");
                require(GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_AUTO_ICONIFY) == originalAutoIconify,
                    "Desktop request did not restore the original native attribute");
                fullscreenKey(client);
                phase++;
                ticks = 0;
            }
            case 4 -> {
                if (++ticks < 8) return false;
                SmokeUi.displayReady(client);
                require(!window.isIconified(), "Fullscreen window remained minimized");
                phase++;
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    private static void fullscreenKey(Minecraft client) {
        require(client.handleGlobalKeyPress(InputConstants.getKey(new KeyEvent(GLFW.GLFW_KEY_F11, 0, 0)), false),
            "Fullscreen shortcut was not handled");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
