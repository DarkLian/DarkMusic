package com.darkmusic.client.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a queued text element so a faded copy can be made.
 */
@Mixin(GuiTextRenderState.class)
interface GuiTextRenderStateAccessor {
    @Accessor("font")
    Font darkmusic$font();

    @Accessor("text")
    FormattedCharSequence darkmusic$text();

    @Accessor("x")
    int darkmusic$x();

    @Accessor("y")
    int darkmusic$y();

    @Accessor("color")
    int darkmusic$color();

    @Accessor("backgroundColor")
    int darkmusic$backgroundColor();

    @Accessor("dropShadow")
    boolean darkmusic$dropShadow();

    @Accessor("includeEmpty")
    boolean darkmusic$includeEmpty();
}
