package com.darkmusic.client.pip;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Client-thread session owner. Capturing a window never changes music or its playback source.
 */
public final class PipController implements AutoCloseable {
    private final DarkMusicClient app;
    public final PipNativeTools tools;
    private final PipTexture texture = new PipTexture(Identifier.fromNamespaceAndPath("darkmusic", "pip"));
    private final com.darkmusic.client.gui.SpectrumRenderer audioSpectrum = new com.darkmusic.client.gui.SpectrumRenderer();
    // Reused rendering adapter: never temporarily swap or edit the user's music preferences.
    private final com.darkmusic.client.config.Settings spectrumAppearance = new com.darkmusic.client.config.Settings();
    private PipSource source;
    private PipSession session;
    private String state = "pip.idle";
    private long generation;
    private boolean preparing, closed;
    private java.util.concurrent.CompletableFuture<Void> stopping = java.util.concurrent.CompletableFuture.completedFuture(null);

    public PipController(DarkMusicClient app) {
        this.app = app;
        tools = new PipNativeTools(app.config.directory(), () -> app.settings != null && app.settings.pip.enabled);
    }

    public static void register(DarkMusicClient app) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkmusic", "pip"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (app.pip != null && mc.level != null && mc.gui.screen() == null && !mc.gui.hud.isHidden())
                app.pip.overlay(g);
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> ScreenEvents.afterExtract(screen).register((s, g, x, y, delta) -> {
            if (app.pip != null && app.settings != null && app.settings.pip.inMenus && !(s instanceof PipPreview))
                app.pip.overlay(g);
        }));
    }

    /**
     * Exclusive fullscreen tears the whole screen while capture runs; the option applies after a restart.
     * The option alone decides: Cubes Without Borders keeps it on while managing fullscreen itself, with the same tearing.
     */
    public static boolean exclusiveFullscreen() {
        return Minecraft.getInstance().options.exclusiveFullscreen().get();
    }

    public PipSource source() {
        return source;
    }

    public void select(PipSource value) {
        if (!app.settings.pip.enabled || !tools.runtime.ready()) return;
        stop();
        source = value;
    }

    public boolean active() {
        return preparing || session != null && session.healthy();
    }

    public String status() {
        return session != null ? session.status() : state;
    }

    public void tick() {
        if ((preparing || session != null) && !app.settings.pip.enabled) stop();
        if (session != null) {
            session.watchdog();
            if (session.audio != null) session.audio.configure(app.settings.pip.spectrum);
        }
    }

    public void start() {
        if (closed || active() || source == null) return;
        if (!app.settings.pip.enabled || !tools.runtime.ready()) {
            state = app.settings.pip.enabled ? "pip.setup.required" : "pip.setup.disabled";
            return;
        }
        if (!PipNativeTools.supported()) {
            state = "pip.unsupported";
            return;
        }
        stop();
        long request = ++generation;
        preparing = true;
        state = "pip.starting";
        app.settings.pip.validate();
        tools.prepare().thenCombine(stopping, (exe, ignored) -> exe).whenComplete((exe, error) -> Minecraft.getInstance().execute(() -> {
            if (closed || request != generation || !app.settings.pip.enabled) return;
            preparing = false;
            if (error != null) {
                state = PipNativeTools.failure(error);
                return;
            }
            long window = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().handle());
            session = new PipSession(exe, source, window, app.settings.pip, app.settings.pip.effectiveFrameRate(monitorRefreshRate()));
            if (session.audio != null) session.audio.configure(app.settings.pip.spectrum);
        }));
    }

    public int monitorRefreshRate() {
        var monitor = Minecraft.getInstance().getWindow().findBestMonitor();
        if (monitor == null) return 0;
        var mode = org.lwjgl.glfw.GLFW.glfwGetVideoMode(monitor.monitor());
        return mode == null ? 0 : mode.refreshRate();
    }

    public void stop() {
        generation++;
        preparing = false;
        if (session != null) {
            session.close();
            stopping = session.termination();
        }
        session = null;
        texture.close();
        state = "pip.idle";
    }

    public void focus() {
        if (session != null) session.command("FOCUS");
    }

    public PipFrame frame() {
        return texture.current();
    }

    public long uploaded() {
        return texture.uploaded();
    }

    public long received() {
        return session == null ? 0 : session.frames.published();
    }

    public long dropped() {
        return session == null ? 0 : session.frames.dropped();
    }

    public PipAudio audio() {
        return session == null ? null : session.audio;
    }

    public long focusSuccesses() {
        return session == null ? 0 : session.focusSuccesses();
    }

    public long focusFailures() {
        return session == null ? 0 : session.focusFailures();
    }

    public PipSettings.Bounds bounds(int width, int height) {
        var f = frame();
        return app.settings.pip.bounds(width, height, f == null ? 16 : f.width(), f == null ? 9 : f.height());
    }

    public void overlay(GuiGraphicsExtractor g) {
        if (session == null || !app.settings.pip.visible) return;
        update();
        draw(g, bounds(g.guiWidth(), g.guiHeight()), false);
    }

    public void update() {
        if (session != null) texture.update(session.frames);
    }

    public void draw(GuiGraphicsExtractor g, PipSettings.Bounds b, boolean fullImage) {
        draw(g, b, fullImage, false);
    }

    public void draw(GuiGraphicsExtractor g, PipSettings.Bounds b, boolean fullImage, boolean preview) {
        var f = frame();
        var settings = app.settings.pip;
        if (f == null) {
            g.fill(b.x(), b.y(), b.x() + b.width(), b.y() + b.height(), 0xff111420);
        } else {
            float u = fullImage ? 0 : (float) (f.width() * settings.left), v = fullImage ? 0 : (float) (f.height() * settings.top);
            int w = fullImage ? f.width() : Math.max(1, (int) (f.width() * (settings.right - settings.left))), h = fullImage ? f.height() : Math.max(1, (int) (f.height() * (settings.bottom - settings.top)));
            int color = ((int) Math.round(settings.opacity * 255) << 24) | 0xffffff;
            g.blit(RenderPipelines.GUI_TEXTURED, texture.id(), b.x(), b.y(), u, v, b.width(), b.height(), w, h, f.width(), f.height(), color);
            // A thin dark frame separates the overlay from bright scenery; it fades with the overlay.
            if (!fullImage)
                g.outline(b.x() - 1, b.y() - 1, b.width() + 2, b.height() + 2, ((int) Math.round(settings.opacity * 0x99) << 24) | 0x0B0D16);
        }
        if (!fullImage && settings.audioSpectrum)
            drawSpectrum(g, settings.spectrum.bounds(b, g.guiWidth(), g.guiHeight()), preview);
        if (!status().equals("pip.live")) {
            g.fill(b.x(), b.y(), b.x() + b.width(), b.y() + 12, 0xcc111420);
            var font = Minecraft.getInstance().font;
            g.text(font, com.darkmusic.client.gui.HorizontalText.ellipsize(font, Text.get(status()), Math.max(1, b.width() - 6)), b.x() + 3, b.y() + 2, com.darkmusic.client.gui.UiStatus.color(com.darkmusic.client.gui.UiStatus.Tone.WARNING));
        }
    }

    /**
     * Preview is the only path allowed to synthesize levels when capture has no audio.
     */
    public void drawSpectrum(GuiGraphicsExtractor g, PipSettings.Bounds b, boolean preview) {
        var s = app.settings.pip.spectrum;
        var captured = audio();
        var analyzer = captured != null && captured.available() ? captured.analyzer() : null;
        if (analyzer == null && !preview) return;
        s.applyTo(spectrumAppearance);
        boolean downward = s.direction == com.darkmusic.client.config.Settings.SpectrumDirection.DOWNWARD;
        if (s.sideways()) {
            boolean left = s.position == com.darkmusic.client.config.Settings.ArtworkSpectrumPosition.LEFT;
            g.pose().pushMatrix();
            try {
                g.pose().translate(b.x() + (left ? 0 : b.width()), b.y() + (left ? b.height() : 0));
                g.pose().rotate((float) (left ? -Math.PI / 2 : Math.PI / 2));
                audioSpectrum.bars(app, g, 0, 0, b.height(), b.width(), false, preview, 0, analyzer, spectrumAppearance);
            } finally {
                g.pose().popMatrix();
            }
        } else
            audioSpectrum.bars(app, g, b.x(), b.y(), b.width(), b.height(), downward, preview, 0, analyzer, spectrumAppearance);
    }

    @Override
    public void close() {
        closed = true;
        stop();
        stopping.whenComplete((done, error) -> tools.runtime.close());
    }
}
