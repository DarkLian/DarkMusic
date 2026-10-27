package com.darkmusic.client.mixin;

import com.darkmusic.client.render.HudFade;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Everything the GUI draws passes through these three methods; while DarkMusic fades its HUD, each element added is
 * replaced by a copy with scaled transparency. Outside a fade they return their argument unchanged.
 */
@Mixin(GuiRenderState.class)
abstract class GuiRenderStateMixin {
    @ModifyVariable(method = "addGuiElement", at = @At("HEAD"), argsOnly = true)
    private GuiElementRenderState darkmusic$fadeElement(GuiElementRenderState element) {
        return HudFade.element(element);
    }

    @ModifyVariable(method = "addBlitToCurrentLayer", at = @At("HEAD"), argsOnly = true)
    private BlitRenderState darkmusic$fadeBlit(BlitRenderState blit) {
        return HudFade.blit(blit);
    }

    @ModifyVariable(method = "addText", at = @At("HEAD"), argsOnly = true)
    private GuiTextRenderState darkmusic$fadeText(GuiTextRenderState text) {
        if (!HudFade.active()) return text;
        var parts = (GuiTextRenderStateAccessor) (Object) text;
        return new GuiTextRenderState(parts.darkmusic$font(), parts.darkmusic$text(), text.pose, parts.darkmusic$x(), parts.darkmusic$y(),
            HudFade.color(parts.darkmusic$color()), parts.darkmusic$backgroundColor() == 0 ? 0 : HudFade.color(parts.darkmusic$backgroundColor()),
            parts.darkmusic$dropShadow(), parts.darkmusic$includeEmpty(), text.scissor);
    }
}
