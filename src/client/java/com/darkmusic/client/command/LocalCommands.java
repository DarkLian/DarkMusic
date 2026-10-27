package com.darkmusic.client.command;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Fabric consumes these commands locally. Feedback never sends chat or command packets.
 */
public final class LocalCommands {
    private LocalCommands() {
    }

    /**
     * What the commands do. Playback actions return their chat reply, or null for none; defaults do nothing so
     * tests implement only what they exercise.
     */
    public interface Actions {
        default ChatPalette palette() {
            return ChatPalette.of(ChatPalette.DEFAULT_ACCENT);
        }

        default String version() {
            return "";
        }

        default void player() {
        }

        default void layout() {
        }

        /**
         * Returns a message key explaining why lyrics cannot open, or "" once they are requested.
         */
        default String lyrics() {
            return "command.darkmusic.unavailable";
        }

        default void pip() {
        }

        default void editKeys() {
        }

        default List<Shortcut> shortcuts() {
            return List.of();
        }

        default void checkUpdate() {
        }

        default void installUpdate() {
        }

        /**
         * Whether a code came from one of DarkMusic's own install buttons in this session.
         */
        default boolean installCode(String code) {
            return false;
        }

        default List<String> songs() {
            return List.of();
        }

        /**
         * A null query resumes playback, or starts the library when nothing is loaded.
         */
        default Component play(String query) {
            return null;
        }

        default Component pause() {
            return null;
        }

        default Component stop() {
            return null;
        }

        default Component next() {
            return null;
        }

        default Component previous() {
            return null;
        }

        default Component now() {
            return null;
        }

        default Component queue(String query) {
            return null;
        }

        /**
         * A null percent reports the volume instead of changing it.
         */
        default Component volume(Integer percent) {
            return null;
        }

        default Component mute() {
            return null;
        }

        default Component speed(Double value) {
            return null;
        }

        default Component seek(String time) {
            return null;
        }

        /**
         * A null value toggles.
         */
        default Component shuffle(Boolean on) {
            return null;
        }

        /**
         * A null mode cycles Off, One, All like the player's button.
         */
        default Component repeat(Repeat mode) {
            return null;
        }
    }

    /**
     * One line of the shortcut list; a null key is unbound.
     */
    public record Shortcut(String label, List<String> keys) {
    }

    /**
     * One clickable command in the help list: the text inserted on click, its argument hint and hover details.
     */
    record Entry(String command, String arguments, String details) {
    }

    record Line(List<Entry> entries, String description) {
    }

    record Group(String title, List<Line> lines) {
    }

    private static Line line(String description, Entry... entries) {
        return new Line(List.of(entries), description);
    }

    // At most 20 lines with the title and links, so the whole list fits in open chat.
    static final List<Group> HELP = List.of(
        new Group("command.darkmusic.section.playback", List.of(
            line("command.darkmusic.play", new Entry("/da play", " [song]", "command.darkmusic.play_details")),
            line("command.darkmusic.pause_stop", new Entry("/da pause", "", "command.darkmusic.pause_details"),
                new Entry("/da stop", "", "command.darkmusic.stop_details")),
            line("command.darkmusic.previous_next", new Entry("/da previous", "", "command.darkmusic.previous_details"),
                new Entry("/da next", "", "command.darkmusic.next_details")),
            line("command.darkmusic.now", new Entry("/da now", "", "command.darkmusic.now_details")),
            line("command.darkmusic.queue", new Entry("/da queue", " <song>", "command.darkmusic.queue_details")),
            line("command.darkmusic.volume", new Entry("/da volume", " [0–100]", "command.darkmusic.volume_details"),
                new Entry("/da mute", "", "command.darkmusic.mute_details")),
            line("command.darkmusic.speed", new Entry("/da speed", " [0.5–3]", "command.darkmusic.speed_details")),
            line("command.darkmusic.seek", new Entry("/da seek", " <time>", "command.darkmusic.seek_details")),
            line("command.darkmusic.shuffle_repeat", new Entry("/da shuffle", " [on|off]", "command.darkmusic.shuffle_details"),
                new Entry("/da repeat", " [off|one|all]", "command.darkmusic.repeat_details")))),
        new Group("command.darkmusic.section.screens", List.of(
            line("command.darkmusic.player", new Entry("/da", "", "command.darkmusic.player_details")),
            line("command.darkmusic.layout", new Entry("/da gui", "", "command.darkmusic.layout_details")),
            line("command.darkmusic.lyrics", new Entry("/da lyrics", "", "command.darkmusic.lyrics_details")),
            line("command.darkmusic.pip", new Entry("/da pip", "", "command.darkmusic.pip_details")))),
        new Group("command.darkmusic.section.more", List.of(
            line("command.darkmusic.keys", new Entry("/da keys", "", "command.darkmusic.keys_details")),
            line("command.darkmusic.update", new Entry("/da update", "", "command.darkmusic.update_details")))));

    public static void register(DarkMusicClient app) {
        var actions = new ClientActions(app);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> register(dispatcher, actions));
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Actions actions) {
        SuggestionProvider<FabricClientCommandSource> songs = (context, builder) -> {
            String typed = builder.getRemainingLowerCase();
            actions.songs().stream().filter(title -> title.toLowerCase(Locale.ROOT).contains(typed))
                .sorted(java.util.Comparator.comparing((String title) -> !title.toLowerCase(Locale.ROOT).startsWith(typed)))
                .limit(50).forEach(builder::suggest);
            return builder.buildFuture();
        };
        for (String name : new String[]{"da", "darkmusic"})
            // Clicked chat buttons run commands "unattended". Requiring attended commands made Minecraft ask for
            // confirmation on every click, so only installing an update checks it, in install().
            dispatcher.register(ClientCommands.literal(name)
                .executes(context -> run(actions::player))
                .then(ClientCommands.literal("gui").executes(context -> run(actions::layout)))
                .then(ClientCommands.literal("pip").executes(context -> run(actions::pip)))
                .then(ClientCommands.literal("lyrics").executes(context -> {
                    String problem = actions.lyrics();
                    if (problem != null && !problem.isEmpty()) {
                        context.getSource().sendFeedback(actions.palette().error(problem));
                        return 0;
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("play")
                    .executes(context -> reply(context, () -> actions.play(null)))
                    .then(ClientCommands.argument("song", StringArgumentType.greedyString()).suggests(songs)
                        .executes(context -> reply(context, () -> actions.play(StringArgumentType.getString(context, "song"))))))
                .then(ClientCommands.literal("queue")
                    .then(ClientCommands.argument("song", StringArgumentType.greedyString()).suggests(songs)
                        .executes(context -> reply(context, () -> actions.queue(StringArgumentType.getString(context, "song"))))))
                .then(simple("pause", actions::pause))
                .then(simple("stop", actions::stop))
                .then(simple("next", actions::next))
                .then(simple("previous", actions::previous))
                .then(simple("now", actions::now))
                .then(simple("mute", actions::mute))
                .then(ClientCommands.literal("volume")
                    .executes(context -> reply(context, () -> actions.volume(null)))
                    .then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 100))
                        .executes(context -> reply(context, () -> actions.volume(IntegerArgumentType.getInteger(context, "percent"))))))
                .then(ClientCommands.literal("speed")
                    .executes(context -> reply(context, () -> actions.speed(null)))
                    .then(ClientCommands.argument("value", DoubleArgumentType.doubleArg(.5, 3))
                        .executes(context -> reply(context, () -> actions.speed(DoubleArgumentType.getDouble(context, "value"))))))
                .then(ClientCommands.literal("seek")
                    .then(ClientCommands.argument("time", StringArgumentType.greedyString())
                        .executes(context -> reply(context, () -> actions.seek(StringArgumentType.getString(context, "time"))))))
                .then(ClientCommands.literal("shuffle")
                    .executes(context -> reply(context, () -> actions.shuffle(null)))
                    .then(ClientCommands.literal("on").executes(context -> reply(context, () -> actions.shuffle(true))))
                    .then(ClientCommands.literal("off").executes(context -> reply(context, () -> actions.shuffle(false)))))
                .then(ClientCommands.literal("repeat")
                    .executes(context -> reply(context, () -> actions.repeat(null)))
                    .then(ClientCommands.literal("off").executes(context -> reply(context, () -> actions.repeat(Repeat.OFF))))
                    .then(ClientCommands.literal("one").executes(context -> reply(context, () -> actions.repeat(Repeat.ONE))))
                    .then(ClientCommands.literal("all").executes(context -> reply(context, () -> actions.repeat(Repeat.ALL)))))
                .then(ClientCommands.literal("keys")
                    .executes(context -> {
                        keys(context.getSource(), actions);
                        return 1;
                    })
                    .then(ClientCommands.literal("edit").executes(context -> run(actions::editKeys))))
                .then(ClientCommands.literal("update")
                    .executes(context -> run(actions::checkUpdate))
                    .then(ClientCommands.literal("install")
                        .executes(context -> install(context, actions, null))
                        .then(ClientCommands.argument("code", StringArgumentType.word())
                            .executes(context -> install(context, actions, StringArgumentType.getString(context, "code"))))))
                .then(ClientCommands.literal("help")
                    .executes(context -> {
                        help(context.getSource(), actions);
                        return 1;
                    })
                    .then(ClientCommands.literal("keys").executes(context -> {
                        keys(context.getSource(), actions);
                        return 1;
                    }))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> simple(String name, Supplier<Component> action) {
        return ClientCommands.literal(name).executes(context -> reply(context, action));
    }

    /**
     * A typed command, or a click on DarkMusic's own button, which carries a code a server cannot know. A link
     * someone else sent cannot start a download.
     */
    private static int install(CommandContext<FabricClientCommandSource> context, Actions actions, String code) {
        if (!context.getSource().attended() && (code == null || !actions.installCode(code))) {
            context.getSource().sendFeedback(actions.palette().error("update.install_typed"));
            return 0;
        }
        actions.installUpdate();
        return 1;
    }

    private static int run(Runnable action) {
        action.run();
        return 1;
    }

    private static int reply(CommandContext<FabricClientCommandSource> context, Supplier<Component> action) {
        var message = action.get();
        if (message != null) context.getSource().sendFeedback(message);
        return 1;
    }

    public static String lyricsProblem(boolean ready, boolean enabled, AudioBackend.State state) {
        if (!ready) return "command.darkmusic.unavailable";
        if (!enabled) return "lyrics.darkmusic.disabled";
        if (state == null || state.track() == null || state.status() == AudioBackend.Status.STOPPED
            || state.status() == AudioBackend.Status.ERROR) return "upnext.idle";
        return "";
    }

    static void help(FabricClientCommandSource source, Actions actions) {
        var palette = actions.palette();
        source.sendFeedback(palette.title(actions.version(), null));
        boolean first = true;
        for (var group : HELP) {
            source.sendFeedback(palette.section(group.title(), first ? palette.key("command.darkmusic.hint", palette.muted()) : null));
            first = false;
            for (var line : group.lines()) source.sendFeedback(helpLine(palette, line));
        }
        source.sendFeedback(palette.link("command.darkmusic.guide", "https://github.com/DarkLian/DarkMusic/blob/main/docs/USER_GUIDE.md")
            .append(palette.plain("  ", palette.muted()))
            .append(palette.link("command.darkmusic.downloads", "https://github.com/DarkLian/DarkMusic/releases/latest"))
            .append(palette.plain("  ", palette.muted()))
            .append(palette.link("command.darkmusic.issues", "https://github.com/DarkLian/DarkMusic/issues")));
    }

    private static MutableComponent helpLine(ChatPalette palette, Line line) {
        var result = palette.plain(" ▸ ", palette.muted());
        for (int i = 0; i < line.entries().size(); i++) {
            if (i > 0) result.append(palette.plain(" · ", palette.muted()));
            result.append(command(palette, line.entries().get(i)));
        }
        return result.append(palette.plain(" — ", palette.muted())).append(palette.key(line.description(), palette.secondary()));
    }

    private static MutableComponent command(ChatPalette palette, Entry entry) {
        String inserted = entry.command() + (entry.arguments().isEmpty() ? "" : " ");
        var hover = palette.key(entry.details(), palette.text())
            .append(palette.plain("\n" + entry.command().replaceFirst("^/da", "/darkmusic") + entry.arguments(), palette.muted()))
            .append(palette.plain("\n", palette.muted())).append(palette.key("command.darkmusic.insert", palette.accent()));
        return palette.plain(entry.command(), palette.text()).append(palette.plain(entry.arguments(), palette.muted()))
            .withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(inserted)).withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    static void keys(FabricClientCommandSource source, Actions actions) {
        var palette = actions.palette();
        source.sendFeedback(palette.title(actions.version(), Text.component("command.darkmusic.section.shortcuts")));
        for (var shortcut : actions.shortcuts()) {
            var line = palette.plain(" ▸ ", palette.muted()).append(palette.key(shortcut.label(), palette.secondary()))
                .append(palette.plain(" — ", palette.muted()));
            for (int i = 0; i < shortcut.keys().size(); i++) {
                if (i > 0) line.append(palette.plain(" / ", palette.muted()));
                String key = shortcut.keys().get(i);
                line.append(key == null ? palette.key("command.darkmusic.unbound", palette.muted()) : palette.plain(key, palette.text()));
            }
            source.sendFeedback(line);
        }
        source.sendFeedback(palette.button("command.darkmusic.change_keys", "/da keys edit", palette.accent())
            .append(palette.plain("  ", palette.muted())).append(palette.key("command.darkmusic.keys_where", palette.muted())));
    }

    /**
     * The best library match for a typed song: exact title, then title prefix, then title text, then any search
     * field (artist, album or file name). Library order breaks ties.
     */
    public static Track find(List<Track> tracks, String query) {
        String wanted = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return null;
        Track prefix = null, contains = null, other = null;
        for (Track track : tracks) {
            String title = track.title() == null ? "" : track.title().toLowerCase(Locale.ROOT);
            if (title.equals(wanted)) return track;
            if (prefix == null && title.startsWith(wanted)) prefix = track;
            else if (contains == null && title.contains(wanted)) contains = track;
            else if (other == null && track.matches(query.strip())) other = track;
        }
        return prefix != null ? prefix : contains != null ? contains : other;
    }

    /**
     * Target position for "/da seek": "1:30" or "1:02:03" or "90" jump there; a leading + or - moves relative to
     * the current position. Returns NaN when the text is not a time, and positive infinity when an exact time is
     * past the end of the song; moves stop at the start or end instead.
     */
    public static double seekTarget(String text, double position, double duration) {
        String value = text == null ? "" : text.strip();
        int sign = value.startsWith("+") ? 1 : value.startsWith("-") ? -1 : 0;
        if (sign != 0) value = value.substring(1).strip();
        if (value.isEmpty() || !value.matches("\\d+(\\.\\d+)?|\\d+(:[0-5]?\\d){1,2}")) return Double.NaN;
        double seconds = 0;
        // Doubles, so an absurdly long number becomes huge or infinite instead of overflowing.
        if (value.contains(":")) for (String part : value.split(":")) seconds = seconds * 60 + Double.parseDouble(part);
        else seconds = Double.parseDouble(value);
        boolean known = Double.isFinite(duration) && duration > 0;
        if (sign == 0) return known && seconds > duration ? Double.POSITIVE_INFINITY : seconds;
        double target = (Double.isFinite(position) ? position : 0) + sign * seconds;
        return Math.clamp(target, 0, known ? duration : Math.max(0, target));
    }

    public static String time(double seconds) {
        long total = Double.isFinite(seconds) ? Math.max(0, Math.round(Math.floor(seconds))) : 0;
        return total >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }
}
