package com.darkmusic.client.mixin;

import com.darkmusic.client.DarkMusicClient;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Only the local background-music scheduler is affected. No networking or sound-effect hooks.
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void darkmusic$suppressBackground(CallbackInfo ci) {
        var app = DarkMusicClient.INSTANCE;
        if (app != null && app.settings != null && app.settings.suppressVanillaMusic) ci.cancel();
    }
}
