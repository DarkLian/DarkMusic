package com.darkmusic.client;

import com.darkmusic.client.gui.PlayerLayout;
import com.darkmusic.client.gui.UiButton;
import com.darkmusic.client.gui.UiInput;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MenuLayoutTest {
    @Test
    void collectionUtilitiesHaveRoomForHistoryBesideManagePlaylists() {
        for (int width : new int[]{320, 426, 480, 639, 640, 854}) {
            var layout = PlayerLayout.of(width, 480);
            int manage = layout.singleColumn() ? width - 64 : layout.navigation().right() - 24;
            int history = manage - 28;
            assertTrue(history >= (layout.singleColumn() ? 126 : 96));
            assertEquals(4, manage - (history + 24));
            assertTrue(manage + 24 <= (layout.singleColumn() ? width - 40 : layout.navigation().right()));
        }
    }

    @Test
    void collectionDescriptionsOmitEmptyLinesAndNarrationSeparators() {
        var collection = new com.darkmusic.client.gui.RowList.Row("All Tracks (30)", "");
        assertEquals("All Tracks (30)", collection.text("\n"));
        assertEquals("All Tracks (30)", collection.text(", "));
        var detailOnly = new com.darkmusic.client.gui.RowList.Row("Favorite Music", "  ", "30 tracks");
        assertEquals("Favorite Music\n30 tracks", detailOnly.text("\n"));
        var full = new com.darkmusic.client.gui.RowList.Row("Title", "Artist", "Album");
        assertEquals("Title\nArtist\nAlbum", full.text("\n"));
    }

    @Test
    void threeColumnListAndControlsAreCenteredIndependently() {
        for (int width : new int[]{640, 641, 799, 800, 801, 854, 1024, 1280, 1720}) {
            var layout = PlayerLayout.of(width, 480);
            assertTrue(layout.inspector().width() > 0);
            assertEquals(width, layout.tracks().x() * 2 + layout.tracks().width());
            assertTrue(Math.abs(width - layout.transportX() * 2 - layout.controlsWidth()) <= 1);
            assertEquals(8, layout.tracks().x() - layout.navigation().right());
            assertEquals(8, layout.inspector().x() - layout.tracks().right());
            assertEquals(104, layout.controlWidth());
            assertEquals(Math.clamp(width / 4, 140, 220), layout.inspector().width());
        }
        var standard = PlayerLayout.of(854, 480);
        assertEquals(854 - 12 - 854 / 4, standard.inspector().x());
        assertEquals(854 / 4, standard.inspector().width());
    }

    @Test
    void centeredTransportFitsAtEverySupportedBreakpointWithSeekAboveControls() {
        for (int width : new int[]{320, 399, 400, 479, 480, 639, 640, 799, 800, 854, 1280})
            for (int height : new int[]{240, 299, 300, 399, 400, 480, 720}) {
                var layout = PlayerLayout.of(width, height);
                assertTrue(layout.transportX() >= layout.dock().x());
                assertTrue(layout.transportX() + layout.controlsWidth() <= layout.dock().right());
                assertTrue(Math.abs(layout.transportX() * 2 + layout.controlsWidth() - width) <= 1);
                assertTrue(layout.seekY() + 20 <= layout.transportY() - 4);
                assertTrue(layout.transportY() + 24 <= layout.dock().bottom());
            }
    }

    @Test
    void artworkFitRecordsOnlyGeneratedGuttersWithoutCroppingOrUpscalingSource() {
        assertEquals(new com.darkmusic.client.library.ArtworkCache.Fit(1024, 1024, 576), com.darkmusic.client.library.ArtworkCache.fit(1024, 576));
        assertEquals(new com.darkmusic.client.library.ArtworkCache.Fit(960, 640, 960), com.darkmusic.client.library.ArtworkCache.fit(640, 960));
        assertEquals(new com.darkmusic.client.library.ArtworkCache.Fit(1024, 1024, 512), com.darkmusic.client.library.ArtworkCache.fit(4096, 2048));
        assertEquals(new com.darkmusic.client.library.ArtworkCache.Fit(32, 32, 32), com.darkmusic.client.library.ArtworkCache.fit(32, 32));
    }

    @Test
    void packagedIconAtlasContainsEveryNativeWidgetIcon() throws Exception {
        try (var jar = new java.util.jar.JarFile(System.getProperty("darkmusic.jar"))) {
            var entry = jar.getJarEntry("assets/darkmusic/textures/gui/icons.png");
            assertNotNull(entry, "The shared icon atlas must ship in the client JAR");
            try (var input = jar.getInputStream(entry)) {
                var atlas = javax.imageio.ImageIO.read(input);
                assertEquals(com.darkmusic.client.gui.UiIcon.values().length * com.darkmusic.client.gui.UiIcon.CELL, atlas.getWidth());
                assertEquals(com.darkmusic.client.gui.UiIcon.CELL, atlas.getHeight());
                for (var icon : com.darkmusic.client.gui.UiIcon.values()) {
                    boolean visible = false;
                    for (int y = 0; y < com.darkmusic.client.gui.UiIcon.CELL; y++)
                        for (int x = 0; x < com.darkmusic.client.gui.UiIcon.CELL; x++)
                            visible |= (atlas.getRGB(icon.ordinal() * com.darkmusic.client.gui.UiIcon.CELL + x, y) >>> 24) != 0;
                    assertTrue(visible, icon + " must have a visible, font-independent sprite");
                    for (int p = 0; p < com.darkmusic.client.gui.UiIcon.CELL; p++) {
                        int left = icon.ordinal() * com.darkmusic.client.gui.UiIcon.CELL;
                        int right = left + com.darkmusic.client.gui.UiIcon.CELL - 1;
                        assertEquals(0, atlas.getRGB(left + p, 0) >>> 24, "Top atlas gutter");
                        assertEquals(0, atlas.getRGB(left + p, com.darkmusic.client.gui.UiIcon.CELL - 1) >>> 24, "Bottom atlas gutter");
                        assertEquals(0, atlas.getRGB(left, p) >>> 24, "Left atlas gutter");
                        assertEquals(0, atlas.getRGB(right, p) >>> 24, "Right atlas gutter");
                    }
                }
            }
        }
    }

    @Test
    void mediaPanelNeverCrowdsTrackListOrOverflowsItsReservedSpace() {
        for (int width : new int[]{320, 426, 479, 480, 481, 639, 640, 641, 799, 800, 801, 854, 1280, 1720})
            for (int height : new int[]{240, 299, 300, 301, 359, 360, 399, 400, 401, 480, 720}) {
                var layout = PlayerLayout.of(width, height);
                assertTrue(layout.tracks().width() >= 240, width + "x" + height);
                assertTrue(layout.tracks().height() >= 36);
                assertTrue(layout.tracks().bottom() < layout.dock().y());
                assertTrue(layout.dock().bottom() <= height - 8);
                if (layout.singleColumn()) assertEquals(0, layout.navigation().width());
                else assertTrue(layout.navigation().right() < layout.tracks().x());
                if (layout.inspector().width() == 0) continue;
                assertTrue(layout.tracks().right() < layout.inspector().x());
                assertTrue(layout.artworkSize() > 0 && layout.artworkSize() <= layout.inspector().width() - 16);
                assertTrue(layout.spectrumY() >= layout.inspector().y() + layout.artworkSize() + 86);
                assertTrue(layout.spectrumY() + layout.spectrumHeight() <= layout.inspector().bottom());
                assertEquals(width - 12, layout.inspector().right());
            }
    }

    @Test
    void skinsRetainNativeInputAndAccessibilityImplementations() {
        assertEquals(Button.class, UiButton.class.getSuperclass());
        assertEquals(EditBox.class, UiInput.class.getSuperclass());
        var nativeMethods = List.of("mouseClicked", "mouseDragged", "keyPressed", "charTyped",
            "updateWidgetNarration", "onPress", "isActive");
        for (var type : List.of(UiButton.class, UiInput.class))
            for (var method : type.getDeclaredMethods())
                assertFalse(nativeMethods.contains(method.getName()), method.toString());
    }

    @Test
    void wideGradientIsStillOneQuadWithExactEndpointColors() throws Exception {
        var type = Class.forName("com.darkmusic.client.gui.UiTheme$Gradient");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var build = type.getDeclaredMethod("buildVertices", VertexConsumer.class);
        build.setAccessible(true);
        for (int width : new int[]{1, 240, 3840}) {
            var colors = new ArrayList<Integer>();
            var positions = new ArrayList<String>();
            var vertices = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertexWith2DPose")) positions.add(args[1] + "," + args[2]);
                    if (method.getName().equals("setColor") && args.length == 1) colors.add((Integer) args[0]);
                    return method.getReturnType() == VertexConsumer.class ? proxy : null;
                });
            var state = constructor.newInstance(new Matrix3x2f(), null, null, 10, 20, width, 8,
                0xFF123456, 0xFFABCDEF);
            build.invoke(state, vertices);
            assertEquals(4, positions.size());
            assertEquals(List.of(0xFF123456, 0xFF123456, 0xFFABCDEF, 0xFFABCDEF), colors);
        }
    }
}
