package com.darkmusic.client.audio;

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
import java.nio.file.Path;
import java.util.Locale;

/**
 * No URL overloads or network sources are exposed.
 */
public final class PcmDecoder {
    private PcmDecoder() {
    }

    public static AudioInputStream open(Path file) throws Exception {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
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
            else if (n.endsWith(".flac"))
                converted = new FlacFormatConversionProvider().getAudioInputStream(pcm, source);
            else converted = AudioSystem.getAudioInputStream(pcm, source);
            return new AudioInputStream(converted, converted.getFormat(), converted.getFrameLength()) {
                @Override
                public void close() throws java.io.IOException {
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
}
