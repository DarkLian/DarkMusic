package com.darkmusic.client.cleanup;

import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.library.RecordingVersions;
import com.darkmusic.client.library.Track;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bounded inverted retrieval, followed by independent evidence checks. No transitive matches.
 */
public final class DuplicateMatcher {
    public record Entry(Track track, String stamp, String hash, String source, int[] fingerprint, Set<Long> audioKeys,
                        boolean verifiedSource) {
        public Entry(Track track, String stamp, String hash, String source, int[] fingerprint) {
            this(track, stamp, hash, source, fingerprint, FingerprintService.keys(fingerprint == null ? new int[0] : fingerprint), true);
        }

        public Entry(Track track, String stamp, String hash, String source, int[] fingerprint, boolean verifiedSource) {
            this(track, stamp, hash, source, fingerprint, FingerprintService.keys(fingerprint == null ? new int[0] : fingerprint), verifiedSource);
        }

        public Entry {
            fingerprint = fingerprint == null ? new int[0] : fingerprint.clone();
            audioKeys = Set.copyOf(audioKeys);
        }
    }

    public record Matches(List<DuplicateResult> pairs, long comparisons, boolean limited) {
    }

    private static final Set<String> DECORATIONS = Set.of("official", "audio", "video", "music", "mv", "pv", "hd", "hq", "4k", "1080p", "lyrics", "lyric", "visualizer", "youtube", "bilibili");
    private static final Set<String> VERSIONS = RecordingVersions.WORDS;
    private static final Set<String> GENERIC_TITLES = Set.of("home", "intro", "outro", "interlude", "untitled", "opening", "ending");

    public static String normalize(String value) {
        String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("\\.(mp3|flac|wav|ogg|opus|m4a|aac|mp4|webm|aiff?)$", "")
            .replaceAll("\\[(?:[a-z0-9_-]{11}|bv[a-z0-9]+)\\]$", "")
            .replaceAll("[\\p{P}\\p{S}\\s_]+", " ").strip();
        return Arrays.stream(s.split(" +")).filter(t -> !DECORATIONS.contains(t)).collect(Collectors.joining(" "));
    }

    static Set<String> tokens(String text) {
        var result = new LinkedHashSet<String>();
        for (String token : normalize(text).split(" +")) {
            if (token.length() > 1) result.add(token);
            if (token.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] cp = token.codePoints().toArray();
                for (int i = 0; i + 1 < cp.length; i++) result.add(new String(cp, i, 2));
            }
        }
        return result;
    }

    static double similarity(String a, String b) {
        a = normalize(a);
        b = normalize(b);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        var x = tokens(a);
        var y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        long common = x.stream().filter(y::contains).count();
        return 2.0 * common / (x.size() + y.size());
    }

    public static String sourceId(String url) {
        // Internal archive identity, shared with the downloader; never treated as an external link.
        if (url != null && url.startsWith("darkmusic-source:") && url.length() <= 8192)
            return url.substring("darkmusic-source:".length());
        try {
            URI u = URI.create(url);
            String host = Objects.toString(u.getHost(), "").toLowerCase(Locale.ROOT);
            String path = Objects.toString(u.getPath(), "");
            var query = new HashMap<String, String>();
            for (String part : Objects.toString(u.getRawQuery(), "").split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            if (host.equals("youtu.be") || host.endsWith(".youtu.be")) return youtube(path.substring(1).split("/")[0]);
            if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
                if (query.containsKey("v")) return youtube(query.get("v"));
                if (path.matches("/(shorts|embed|live)/[^/]+.*")) return youtube(path.split("/")[2]);
            }
            if (host.equals("bilibili.com") || host.endsWith(".bilibili.com")) {
                var m = Pattern.compile("/(BV[A-Za-z0-9]+|av[0-9]+)", Pattern.CASE_INSENSITIVE).matcher(path);
                if (m.find()) return "bilibili:" + m.group(1) + ":" + query.getOrDefault("p", "1");
            }
            // No arbitrary URL query stripping: unrelated generic collection entries must not collapse.
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static String youtube(String id) {
        return id.matches("[A-Za-z0-9_-]{11}") ? "youtube:" + id : "";
    }

    public Matches match(List<Entry> entries, DuplicatePolicy policy, ProcessRunner.Token token, Consumer<List<DuplicateResult>> partial) {
        var session = new Session(policy, token);
        for (int i = 0; i < entries.size(); i++) {
            session.add(entries.get(i));
            if (i % 1000 == 0 && !session.output.isEmpty()) partial.accept(session.snapshot().pairs());
            if (session.output.size() >= DuplicatePolicy.MAX_RESULTS) break;
        }
        return session.snapshot();
    }

    /**
     * Worker-owned retrieval index reused as newly decoded fingerprints arrive.
     */
    public static final class Session {
        private final DuplicatePolicy policy;
        private final ProcessRunner.Token token;
        private final List<Entry> entries = new ArrayList<>();
        private final Map<String, ArrayList<Integer>> indexes = new HashMap<>();
        private final Map<String, DuplicateResult> output = new LinkedHashMap<>();
        private long comparisons;
        private boolean limited;

        public Session(DuplicatePolicy policy, ProcessRunner.Token token) {
            this.policy = policy;
            this.token = token;
        }

        public void add(Entry entry) {
            int index = entries.size();
            entries.add(entry);
            update(index, entry);
        }

        public void update(int index, Entry entry) {
            token.check();
            entries.set(index, entry);
            var keys = keys(entry);
            var votes = new HashMap<Integer, Integer>();
            for (String key : keys) {
                var bucket = indexes.get(key);
                if (bucket != null) for (int other : bucket) if (other != index) votes.merge(other, 1, Integer::sum);
            }
            var retrieved = votes.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).limit(DuplicatePolicy.MAX_RETRIEVED).toList();
            if (votes.size() > retrieved.size()) limited = true;
            for (var candidate : retrieved) {
                token.check();
                comparisons++;
                var other = entries.get(candidate.getKey());
                var result = compare(other, entry, policy);
                if (result != null) {
                    if (output.size() >= DuplicatePolicy.MAX_RESULTS && !output.containsKey(result.id())) {
                        limited = true;
                        continue;
                    }
                    output.put(result.id(), result);
                }
            }
            for (String key : keys) {
                var bucket = indexes.computeIfAbsent(key, k -> new ArrayList<>());
                if (bucket.contains(index)) continue;
                if (bucket.size() < DuplicatePolicy.BUCKET_SIZE) bucket.add(index);
                else {
                    bucket.set(32 + index % 32, index);
                    limited = true;
                }
            }
        }

        public Matches snapshot() {
            var sorted = new ArrayList<>(output.values());
            sorted.sort(Comparator.comparing(DuplicateResult::strength).thenComparing(r -> r.a().title()));
            return new Matches(List.copyOf(sorted), comparisons, limited);
        }
    }

    private static Set<String> keys(Entry e) {
        var keys = new LinkedHashSet<String>();
        if (!e.hash().isEmpty()) keys.add("h:" + e.hash());
        String source = sourceId(e.source());
        if (!source.isEmpty()) keys.add("s:" + source);
        String title = normalize(e.track().title()), name = normalize(e.track().file().getFileName().toString());
        if (!title.isEmpty()) keys.add("t:" + title);
        if (!name.isEmpty()) keys.add("n:" + name);
        // Token keys are duration-bucketed; adjacent buckets preserve tolerance near boundaries.
        int duration = (int) (e.track().duration() / 15);
        var words = new LinkedHashSet<>(tokens(title));
        words.addAll(tokens(name));
        for (String word : words.stream().sorted(Comparator.comparingInt(String::length).reversed()).limit(12).toList())
            for (int d = -1; d <= 1; d++) keys.add("w:" + word + ":" + (duration + d));
        for (long key : e.audioKeys()) keys.add("a:" + key);
        return keys;
    }

    public static DuplicateResult compare(Entry a, Entry b, DuplicatePolicy p) {
        if (a.track().id().equals(b.track().id()) || a.track().file().equals(b.track().file())) return null;
        var reasons = EnumSet.noneOf(DuplicateResult.Evidence.class);
        if (!a.hash().isEmpty() && a.hash().equals(b.hash())) reasons.add(DuplicateResult.Evidence.EXACT);
        String source = sourceId(a.source());
        if (!source.isEmpty() && source.equals(sourceId(b.source())))
            reasons.add(a.verifiedSource() && b.verifiedSource() ? DuplicateResult.Evidence.SOURCE : DuplicateResult.Evidence.SOURCE_TAG);
        boolean length = p.duration(a.track().duration(), b.track().duration());
        double title = similarity(a.track().title(), b.track().title());
        double name = similarity(a.track().file().getFileName().toString(), b.track().file().getFileName().toString());
        if (title >= p.title && length) reasons.add(DuplicateResult.Evidence.TITLE_DURATION);
        if (name >= p.filename && (length || p == DuplicatePolicy.LOOSE && similarity(a.track().artist(), b.track().artist()) >= p.artist))
            reasons.add(DuplicateResult.Evidence.FILENAME);
        var audio = FingerprintService.compare(a.fingerprint(), b.fingerprint(), a.audioKeys(), b.audioKeys(), p);
        if (audio.matched())
            reasons.add(audio.partial() ? DuplicateResult.Evidence.OVERLAP : DuplicateResult.Evidence.AUDIO);
        if (GENERIC_TITLES.contains(normalize(a.track().title()))
            && !normalize(a.track().artist()).isEmpty() && !normalize(b.track().artist()).isEmpty()
            && similarity(a.track().artist(), b.track().artist()) < p.artist) {
            reasons.remove(DuplicateResult.Evidence.TITLE_DURATION);
            reasons.remove(DuplicateResult.Evidence.FILENAME);
        }
        if (p == DuplicatePolicy.STRICT && !reasons.contains(DuplicateResult.Evidence.EXACT) && !reasons.contains(DuplicateResult.Evidence.SOURCE)
            && !reasons.contains(DuplicateResult.Evidence.AUDIO) && !reasons.contains(DuplicateResult.Evidence.OVERLAP)
            && similarity(a.track().artist(), b.track().artist()) < p.artist) reasons.clear();
        if (reasons.isEmpty()) return null;
        var va = tokens(a.track().title() + " " + a.track().file().getFileName());
        va.retainAll(VERSIONS);
        var vb = tokens(b.track().title() + " " + b.track().file().getFileName());
        vb.retainAll(VERSIONS);
        boolean versions = !va.equals(vb);
        if (versions) reasons.add(DuplicateResult.Evidence.VERSION);
        var strength = reasons.contains(DuplicateResult.Evidence.EXACT) ? DuplicateResult.Strength.STRONG : versions ? DuplicateResult.Strength.VERSIONS
            : reasons.contains(DuplicateResult.Evidence.SOURCE) || reasons.contains(DuplicateResult.Evidence.AUDIO) ? DuplicateResult.Strength.STRONG : DuplicateResult.Strength.POSSIBLE;
        return new DuplicateResult(DuplicateResult.pair(a.track().id(), b.track().id()), a.track(), b.track(), a.stamp(), b.stamp(), reasons, strength);
    }
}
