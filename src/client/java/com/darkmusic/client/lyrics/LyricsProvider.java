package com.darkmusic.client.lyrics;

import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.library.Track;

import java.util.List;

public interface LyricsProvider {
    record Query(String title, String artist, String album, double duration) {
        public Query {
            title = bounded(title);
            artist = bounded(artist);
            album = bounded(album);
            if (!Double.isFinite(duration) || duration < 0) duration = 0;
        }

        private static String bounded(String text) {
            return text == null ? "" : text.strip().substring(0, Math.min(text.strip().length(), 512));
        }

        public static Query of(Track track) {
            String title = track.title();
            String artist = track.artist();
            // Download titles often repeat an already known artist before the actual title.
            if (!artist.isBlank() && title.regionMatches(true, 0, artist, 0, artist.length())) {
                String remainder = title.substring(artist.length()).stripLeading();
                if (remainder.matches("^[-–—]\\s+.+")) title = remainder.substring(1).strip();
            }
            title = title.replaceAll("(?i)[(\\[]\\s*(?:official\\s+)?(?:audio|music video|lyric video|lyrics video|lyrics)\\s*[)\\]]", " ").strip();
            return new Query(title, artist, track.album(), track.duration());
        }
    }

    record Candidate(LyricsDocument document, boolean reliable, String evidence) {
    }

    String id();

    List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception;

    default List<Candidate> alternatives(Query query, ProcessRunner.Token token) throws Exception {
        return search(query, token);
    }
}
