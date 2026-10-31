package com.darkmusic.client.mixin;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.ListeningMix;
import com.darkmusic.client.config.AudioSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Shadow
    public abstract void refreshCategoryVolume(SoundSource source);

    @Inject(method = "tick", at = @At("HEAD"))
    private void darkmusic$mix(boolean paused, CallbackInfo ci) {
        var app = DarkMusicClient.INSTANCE;
        var services = app == null ? null : app.services();
        boolean playing = services != null && services.settings() != null && services.playback() != null
            && Minecraft.getInstance().level != null && services.playback().state().status() == AudioBackend.Status.PLAYING;
        if (ListeningMix.tick(darkmusic$audio(), playing)) refreshCategoryVolume(SoundSource.MASTER);
    }

    @Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
    private void darkmusic$volume(float volume, SoundSource source, CallbackInfoReturnable<Float> ci) {
        ci.setReturnValue((float) (ci.getReturnValueF() * ListeningMix.category(source.name())));
    }

    @Inject(method = "play", at = @At("RETURN"))
    private void darkmusic$priority(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> ci) {
        if (ci.getReturnValue() != SoundEngine.PlayResult.NOT_STARTED && sound.getVolume() > 0)
            ListeningMix.sound(sound.getSource().name(), sound.getIdentifier().toString(), darkmusic$audio());
    }

    @Unique
    private static AudioSettings darkmusic$audio() {
        var app = DarkMusicClient.INSTANCE;
        return app == null || app.services().settings() == null ? null : app.services().settings().audio;
    }
}
