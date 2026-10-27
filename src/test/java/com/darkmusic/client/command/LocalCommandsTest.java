package com.darkmusic.client.command;

import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.UiTheme;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalCommandsTest {
    private final List<Component> messages = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private FabricClientCommandSource source() {
        return source(true);
    }

    /**
     * Attended sources are typed commands; clicked chat buttons run unattended.
     */
    private FabricClientCommandSource source(boolean attended) {
        return (FabricClientCommandSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{FabricClientCommandSource.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "attended" -> attended;
                case "sendFeedback" -> {
                    messages.add((Component) args[0]);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected command interaction: " + method.getName());
            };
        });
    }

    private Component record(String call) {
        calls.add(call);
        return Component.literal(call);
    }

    private CommandDispatcher<FabricClientCommandSource> dispatcher() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        LocalCommands.register(dispatcher, new LocalCommands.Actions() {
            @Override
            public Component play(String query) {
                return record("play " + query);
            }

            @Override
            public Component pause() {
                return record("pause");
            }

            @Override
            public Component stop() {
                return record("stop");
            }

            @Override
            public Component next() {
                return record("next");
            }

            @Override
            public Component previous() {
                return record("previous");
            }

            @Override
            public Component now() {
                return record("now");
            }

            @Override
            public Component queue(String query) {
                return record("queue " + query);
            }

            @Override
            public Component volume(Integer percent) {
                return record("volume " + percent);
            }

            @Override
            public Component mute() {
                return record("mute");
            }

            @Override
            public Component speed(Double value) {
                return record("speed " + value);
            }

            @Override
            public Component seek(String time) {
                return record("seek " + time);
            }

            @Override
            public Component shuffle(Boolean on) {
                return record("shuffle " + on);
            }

            @Override
            public Component repeat(Repeat mode) {
                return record("repeat " + mode);
            }

            @Override
            public Component sleep(Integer minutes) {
                return record("sleep " + minutes);
            }

            @Override
            public Component stopAfter() {
                return record("stopafter");
            }

            @Override
            public void checkUpdate() {
                calls.add("check");
            }

            @Override
            public void installUpdate() {
                calls.add("install");
            }

            @Override
            public boolean installCode(String code) {
                return "c0de".equals(code);
            }

            @Override
            public void editKeys() {
                calls.add("edit keys");
            }

            @Override
            public List<String> songs() {
                return List.of("Departure", "Midnight Drive", "Drive Home");
            }

            @Override
            public List<LocalCommands.Shortcut> shortcuts() {
                return List.of(new LocalCommands.Shortcut("key.darkmusic.open", List.of("B")),
                    new LocalCommands.Shortcut("command.darkmusic.keys.volume", java.util.Arrays.asList(null, "Up Arrow")));
            }
        });
        return dispatcher;
    }

    @Test
    void playbackCommandsPassTheirArgumentsToTheClientAndReplyInChat() throws Exception {
        var dispatcher = dispatcher();
        var source = source();
        for (String alias : List.of("da", "darkmusic")) {
            for (String command : List.of("play", "play  Midnight Drive ", "pause", "stop", "next", "previous", "now", "queue Drive Home",
                "volume", "volume 40", "mute", "speed", "speed 1.5", "seek 1:30", "seek -10", "shuffle", "shuffle on", "shuffle off",
                "repeat", "repeat one", "repeat all", "repeat off", "sleep", "sleep 30", "sleep off", "stopafter"))
                assertEquals(1, dispatcher.execute(alias + " " + command, source));
        }
        var expected = List.of("play null", "play  Midnight Drive ", "pause", "stop", "next", "previous", "now", "queue Drive Home",
            "volume null", "volume 40", "mute", "speed null", "speed 1.5", "seek 1:30", "seek -10", "shuffle null", "shuffle true",
            "shuffle false", "repeat null", "repeat ONE", "repeat ALL", "repeat OFF", "sleep null", "sleep 30", "sleep 0", "stopafter");
        var both = new ArrayList<>(expected);
        both.addAll(expected);
        assertEquals(both, calls);
        assertEquals(both, messages.stream().map(Component::getString).toList());
    }

    @Test
    void outOfRangeValuesAreRejectedBeforeReachingPlayback() {
        var dispatcher = dispatcher();
        var source = source();
        for (String command : List.of("da volume 101", "da volume -1", "da speed 0.4", "da speed 3.5", "da shuffle maybe", "da repeat twice", "da queue", "da sleep 0", "da sleep 721"))
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source), command);
        assertEquals(List.of(), calls);
    }

    @Test
    void updateChecksWithoutDownloadingUntilInstallIsRequested() throws Exception {
        var dispatcher = dispatcher();
        var source = source();
        dispatcher.execute("da update", source);
        assertEquals(List.of("check"), calls);
        dispatcher.execute("darkmusic update install", source);
        assertEquals(List.of("check", "install"), calls);
    }

    @Test
    void clickedButtonsRunWithoutConfirmationButOnlyDarkMusicsOwnButtonCanInstall() throws Exception {
        var dispatcher = dispatcher();
        var clicked = source(false);
        // Fabric asks for confirmation when a clicked command fails to parse unattended but parses when typed.
        for (String command : List.of("da now", "da pause", "da play", "da volume 40", "da keys edit", "da lyrics", "da update install c0de")) {
            var parse = dispatcher.parse(command, clicked);
            assertFalse(parse.getReader().canRead(), command);
            assertTrue(parse.getExceptions().isEmpty(), command);
        }
        assertEquals(1, dispatcher.execute("da keys edit", clicked));
        assertEquals(1, dispatcher.execute("da volume 40", clicked));
        assertEquals(List.of("edit keys", "volume 40"), calls);
        calls.clear();
        assertEquals(0, dispatcher.execute("da update install", clicked));
        assertEquals(0, dispatcher.execute("da update install guess", clicked));
        assertEquals(List.of(), calls);
        assertEquals(com.darkmusic.client.text.Text.get("update.install_typed"), messages.getLast().getString());
        assertEquals(1, dispatcher.execute("da update install c0de", clicked));
        assertEquals(1, dispatcher.execute("darkmusic update install", source(true)));
        assertEquals(List.of("install", "install"), calls);
    }

    @Test
    void songArgumentsSuggestLibraryTitlesWithPrefixMatchesFirst() throws Exception {
        var dispatcher = dispatcher();
        var source = source();
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("da play dri", source)).get();
        assertEquals(List.of("Drive Home", "Midnight Drive"), suggestions.getList().stream().map(s -> s.getText()).toList());
        suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("da queue dep", source)).get();
        assertEquals(List.of("Departure"), suggestions.getList().stream().map(s -> s.getText()).toList());
    }

    @Test
    void keyListShowsCurrentKeysUnboundKeysAndAChangeButton() throws Exception {
        var dispatcher = dispatcher();
        var source = source();
        dispatcher.execute("da keys", source);
        assertEquals(4, messages.size());
        assertTrue(messages.get(1).getString().endsWith("B"));
        assertTrue(messages.get(2).getString().endsWith("Not bound / Up Arrow"), messages.get(2).getString());
        var button = messages.get(3).toFlatList().stream().map(part -> part.getStyle().getClickEvent())
            .filter(ClickEvent.RunCommand.class::isInstance).map(ClickEvent.RunCommand.class::cast).findFirst().orElseThrow();
        assertEquals("/da keys edit", button.command());
        messages.clear();
        dispatcher.execute("da help keys", source);
        assertEquals(4, messages.size());
        dispatcher.execute("da keys edit", source);
        assertEquals(List.of("edit keys"), calls);
    }

    private static Track song(String title, String artist) {
        return new Track("root", title + ".mp3", Path.of(title + ".mp3"), title, artist, "Album", 180, 1, 0, "");
    }

    @Test
    void songMatchingPrefersExactTitlesThenPrefixesThenAnyField() {
        var drive = song("Midnight Drive", "Kavinsky");
        var home = song("Drive Home", "Steven Wilson");
        var exact = song("Drive", "Someone");
        var tracks = List.of(drive, home, exact);
        assertSame(exact, LocalCommands.find(tracks, "drive"));
        assertSame(home, LocalCommands.find(List.of(drive, home), " DRIVE "));
        assertSame(drive, LocalCommands.find(tracks, "midnight"));
        assertSame(home, LocalCommands.find(tracks, "wilson"));
        assertNull(LocalCommands.find(tracks, "nothing like this"));
        assertNull(LocalCommands.find(tracks, "  "));
    }

    @Test
    void seekAcceptsClockTimesSecondsAndRelativeMovesWithinTheSong() {
        assertEquals(90, LocalCommands.seekTarget("1:30", 10, 200));
        assertEquals(90, LocalCommands.seekTarget("90", 10, 200));
        assertEquals(62.5, LocalCommands.seekTarget("62.5", 10, 200));
        assertEquals(3723, LocalCommands.seekTarget("1:02:03", 0, 4000));
        assertEquals(20, LocalCommands.seekTarget("+10", 10, 200));
        assertEquals(0, LocalCommands.seekTarget("-30", 10, 200));
        assertEquals(200, LocalCommands.seekTarget("3:20", 10, 200));
        assertEquals(200, LocalCommands.seekTarget("+ 1:00", 190, 200));
        // An exact time past the end is refused rather than ending the song; moves stop at either end.
        for (String past : List.of("5:00", "201", "20000000000000000", "99999999999:00", "9".repeat(400)))
            assertEquals(Double.POSITIVE_INFINITY, LocalCommands.seekTarget(past, 10, 200), past);
        assertEquals(200, LocalCommands.seekTarget("+20000000000000000", 10, 200));
        assertEquals(0, LocalCommands.seekTarget("-99999999999:00", 10, 200));
        for (String invalid : List.of("", "abc", "1:", "1:75", "--5", "1:2:3:4", "1e3"))
            assertTrue(Double.isNaN(LocalCommands.seekTarget(invalid, 10, 200)), invalid);
        assertEquals("0:00", LocalCommands.time(Double.NaN));
        assertEquals("1:05", LocalCommands.time(65.9));
        assertEquals("1:01:01", LocalCommands.time(3661));
        assertEquals("1:23 / 3:45", com.darkmusic.client.text.Clock.progress(83.9, 225));
        assertEquals("0:07", com.darkmusic.client.text.Clock.progress(7, Double.NaN), "Unknown length shows only the position");
    }

    @Test
    void chatPaletteKeepsTheMenuAccentReadableOnTheDarkChatBackdrop() {
        assertEquals(ChatPalette.DEFAULT_ACCENT, ChatPalette.of(ChatPalette.DEFAULT_ACCENT).accent());
        for (int accent : new int[]{0x000000, 0x202060, 0x7A1F2B, 0xFFFFFF}) {
            int shown = ChatPalette.of(accent).accent();
            assertTrue(UiTheme.contrast(shown | 0xFF000000, 0xFF101118) >= 4.5, Integer.toHexString(accent));
        }
    }
}
