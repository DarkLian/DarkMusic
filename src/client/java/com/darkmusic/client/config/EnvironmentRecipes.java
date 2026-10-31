package com.darkmusic.client.config;

import com.darkmusic.client.config.SoundEnvironmentSettings.Parameters;
import com.darkmusic.client.config.SoundEnvironmentSettings.Preset;

/**
 * Fresh immutable-by-convention built-in recipes; each request receives its own parameters and arrays.
 */
final class EnvironmentRecipes {
    private EnvironmentRecipes() {
    }

    static Parameters recipe(Preset preset) {
        var p = new Parameters();
        switch (preset) {
            case OLD_RADIO -> {
                p.mono = true;
                p.highPass = 250;
                p.lowPass = 4200;
                p.presenceGain = 3;
                p.drive = 1.3;
                p.modulationMs = .25;
                p.noiseDb = -48;
            }
            case TELEPHONE -> {
                p.mono = true;
                p.highPass = 320;
                p.lowPass = 3300;
                p.presenceFrequency = 1600;
                p.presenceGain = 2;
                p.drive = .6;
                p.noiseDb = -64;
            }
            case WALKIE_TALKIE -> {
                p.mono = true;
                p.highPass = 430;
                p.lowPass = 2800;
                p.presenceFrequency = 2200;
                p.presenceGain = 4;
                p.drive = 2;
                p.noiseDb = -51;
            }
            case VINYL -> {
                p.highPass = 35;
                p.lowPass = 15500;
                p.drive = .15;
                p.modulationMs = .35;
                p.modulationHz = .55;
                p.noiseDb = -52;
            }
            case CASSETTE -> {
                p.highPass = 45;
                p.lowPass = 11500;
                p.drive = .75;
                p.modulationMs = .8;
                p.modulationHz = 1.2;
                p.noiseDb = -46;
            }
            case BEDROOM ->
                space(p, .65, .35, .68, .10, .008, new double[]{.009, .017, .026}, new double[]{.10, .07, .04});
            case BATHROOM -> {
                space(p, .55, .8, .12, .20, .004, new double[]{.006, .013, .019, .031}, new double[]{.13, .11, .09, .05});
                p.presenceFrequency = 2800;
                p.presenceGain = 1;
            }
            case CONCERT_HALL ->
                space(p, 1.5, 2.1, .42, .23, .035, new double[]{.033, .057, .083}, new double[]{.07, .06, .04});
            case CATHEDRAL ->
                space(p, 2.2, 5.5, .32, .32, .085, new double[]{.07, .113, .179, .251}, new double[]{.07, .06, .05, .04});
            case DEEP_CAVE -> {
                space(p, 1.9, 4.1, .82, .28, .065, new double[]{.103, .237, .389}, new double[]{.12, .09, .06});
                p.lowPass = 6500;
                p.echoTime = .43;
                p.echoGain = .27;
            }
            case TUNNEL -> {
                space(p, 1.3, 1.7, .57, .15, .02, new double[]{.12, .24, .36}, new double[]{.16, .10, .06});
                p.echoTime = .26;
                p.echoGain = .38;
                p.pan = -.1;
            }
            case WAREHOUSE -> {
                space(p, 1.7, 1.5, .26, .20, .024, new double[]{.047, .091, .143}, new double[]{.15, .10, .06});
                p.echoTime = .15;
                p.echoGain = .16;
            }
            case STADIUM -> {
                space(p, 2.0, 2.5, .65, .12, .12, new double[]{.21, .47, .79}, new double[]{.10, .08, .05});
                p.echoTime = .62;
                p.echoGain = .32;
                p.distance = .3;
            }
            case OPEN_MEADOW -> {
                p.acoustic = true;
                p.lowPass = 14000;
                p.distance = .15;
                p.noiseDb = -58;
            }
            case FOREST_CLEARING -> {
                p.acoustic = true;
                p.lowPass = 11000;
                p.distance = .18;
                p.reflectionTimes = new double[]{.052, .117, .193};
                p.reflectionGains = new double[]{.045, .025, .012};
                p.noiseDb = -60;
            }
            case NEXT_ROOM -> {
                space(p, .8, .45, .85, .10, .016, new double[]{.014, .038, .059}, new double[]{.05, .03, .02});
                p.lowPass = 1100;
                p.distance = .55;
                p.highPass = 35;
            }
            case DISTANT_SPEAKERS -> {
                p.acoustic = true;
                p.lowPass = 4800;
                p.highPass = 90;
                p.distance = .65;
                p.echoTime = .32;
                p.echoGain = .06;
            }
            case UNDERWATER -> {
                p.lowPass = 750;
                p.presenceFrequency = 350;
                p.presenceGain = 2;
                p.modulationMs = 1.3;
                p.modulationHz = .35;
                p.noiseDb = -58;
            }
            case DREAMSCAPE -> {
                space(p, 1.5, 2.8, .65, .25, .045, new double[]{.08, .137, .221}, new double[]{.10, .07, .05});
                p.lowPass = 10000;
                p.modulationMs = 2.2;
                p.modulationHz = .3;
                p.echoGain = .16;
                p.echoTime = .37;
            }
            case CAR_CABIN -> {
                space(p, .45, .22, .78, .08, .003, new double[]{.004, .009, .016}, new double[]{.13, .08, .04});
                p.highPass = 35;
                p.lowPass = 8500;
                p.presenceFrequency = 150;
                p.presenceGain = 3.5;
            }
            case STAIRWELL -> {
                space(p, 1.15, 2.1, .22, .17, .012, new double[]{.061, .127, .263, .521}, new double[]{.15, -.10, .075, -.035});
                p.highPass = 80;
                p.lowPass = 15500;
                p.echoTime = .185;
                p.echoGain = .21;
            }
            case ORBITAL_ECHO -> {
                space(p, 1.8, 3.4, .42, .22, .075, new double[]{.173, .347, .691}, new double[]{.075, -.055, .035});
                p.highPass = 100;
                p.lowPass = 12500;
                p.modulationMs = .8;
                p.modulationHz = .12;
                p.echoTime = .73;
                p.echoGain = .32;
            }
            default -> {
            }
        }
        return p;
    }

    private static void space(Parameters p, double size, double decay, double damping, double wet, double predelay, double[] times, double[] gains) {
        p.acoustic = true;
        p.roomSize = size;
        p.decay = decay;
        p.damping = damping;
        p.wet = wet;
        p.predelay = predelay;
        p.reflectionTimes = times;
        p.reflectionGains = gains;
    }
}
