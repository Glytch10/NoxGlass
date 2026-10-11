package dev.imb11.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftRendererAccessor {
    @Mutable
    @Accessor("levelRenderer")
    void glass$levelRenderer(LevelRenderer renderer);
}
