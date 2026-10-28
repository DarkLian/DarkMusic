package com.darkmusic.client.audio;

import com.darkmusic.client.media.AudioFormats;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.media.OpenedAudio;
import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;
import javazoom.spi.vorbis.sampled.convert.VorbisFormatConversionProvider;
import javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader;
import org.jflac.sound.spi.FlacAudioFileReader;
import org.jflac.sound.spi.FlacFormatConversionProvider;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

/**
 * No URL overloads or network sources are exposed.
 */
public final class PcmDecoder {
    public static OpenedAudio openAt(Path file, double seconds) throws Exception {
        try {
            ExternalAudio.tool(false);
            ExternalAudio.tool(true);
        } catch (IOException unavailable) {
            return new OpenedAudio(openPrecise(file), 0);
        }
        return ExternalAudio.openAt(file, seconds);
    }

    private PcmDecoder() {
    }

    public static AudioInputStream open(Path file) throws Exception {
        var type = AudioFormats.detect(file);
        if (type == AudioFormats.Container.OPUS || type == AudioFormats.Container.MP4 || type == AudioFormats.Container.AAC || type == AudioFormats.Container.WEBM)
            return sixteen(ExternalAudio.open(file));
        if (type == AudioFormats.Container.UNKNOWN) {
            boolean toolsAvailable;
            try {
                ExternalAudio.tool(false);
                ExternalAudio.tool(true);
                toolsAvailable = true;
            } catch (IOException missing) {
                toolsAvailable = false;
            }
            if (toolsAvailable) return sixteen(ExternalAudio.open(file));
        }
        String n = switch (type) {
            case MP3 -> ".mp3";
            case FLAC -> ".flac";
            case VORBIS -> ".ogg";
            case WAVE, AIFF -> ".wav";
            default -> file.getFileName().toString().toLowerCase(Locale.ROOT);
        };
        AudioInputStream source;
        if (n.endsWith(".mp3")) source = new MpegAudioFileReader().getAudioInputStream(file.toFile());
        else if (n.endsWith(".ogg")) source = new VorbisAudioFileReader().getAudioInputStream(file.toFile());
        else if (n.endsWith(".flac")) source = new FlacAudioFileReader().getAudioInputStream(file.toFile());
        else source = AudioSystem.getAudioInputStream(file.toFile());
        try {
            var format = source.getFormat();
            if (format.getChannels() < 1 || format.getChannels() > 2 || format.getSampleRate() <= 0)
                throw new UnsupportedAudioFileException("Only mono/stereo audio with a known sample rate is supported");
            var pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16,
                format.getChannels(), format.getChannels() * 2, format.getSampleRate(), false);
            AudioInputStream converted;
            if (n.endsWith(".mp3")) converted = new MpegFormatConversionProvider().getAudioInputStream(pcm, source);
            else if (n.endsWith(".ogg"))
                converted = new VorbisFormatConversionProvider().getAudioInputStream(pcm, source);
            else if (n.endsWith(".flac")) {
                var nativePcm = new AudioFormat(format.getSampleRate(), format.getSampleSizeInBits(), format.getChannels(), true, false);
                var decoded = new FlacFormatConversionProvider().getAudioInputStream(nativePcm, source);
                converted = nativePcm.matches(pcm) ? decoded : AudioSystem.getAudioInputStream(pcm, decoded);
            } else converted = AudioSystem.getAudioInputStream(pcm, source);
            return new AudioInputStream(converted, converted.getFormat(), converted.getFrameLength()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        source.close();
                    }
                }
            };
        } catch (Exception e) {
            source.close();
            throw e;
        }
    }

    public static AudioInputStream openPrecise(Path file) throws Exception {
        // FFmpeg honors codec delay/padding and supplies float PCM without a lossy intermediate.
        try {
            ExternalAudio.tool(false);
            ExternalAudio.tool(true);
        } catch (IOException unavailable) {
            var type = AudioFormats.detect(file);
            if (type == AudioFormats.Container.FLAC) {
                var source = new FlacAudioFileReader().getAudioInputStream(file.toFile());
                try {
                    var f = source.getFormat();
                    return new FlacFormatConversionProvider().getAudioInputStream(new AudioFormat(f.getSampleRate(), f.getSampleSizeInBits(), f.getChannels(), true, false), source);
                } catch (Exception e) {
                    source.close();
                    throw e;
                }
            }
            if (type == AudioFormats.Container.WAVE || type == AudioFormats.Container.AIFF) {
                var source = AudioSystem.getAudioInputStream(file.toFile());
                var f = source.getFormat();
                if (!f.isBigEndian() && (f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) || f.getEncoding().equals(AudioFormat.Encoding.PCM_FLOAT)) && f.getSampleSizeInBits() >= 16 && f.getSampleSizeInBits() <= 32)
                    return source;
                source.close();
            }
            return open(file);
        }
        return ExternalAudio.open(file);
    }

    private static AudioInputStream sixteen(AudioInputStream source) {
        var rate = new PcmRateConverter(source);
        var f = source.getFormat();
        return new AudioInputStream(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Read complete PCM frames");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (off == 0) return rate.read(b, len, 1);
                byte[] data = new byte[len];
                int n = rate.read(data, len, 1);
                if (n > 0) System.arraycopy(data, 0, b, off, n);
                return n;
            }

            @Override
            public void close() throws IOException {
                source.close();
            }
        }, new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false), -1);
    }
}
