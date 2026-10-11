package dev.imb11.mixins.compat;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.imb11.client.compat.IrisCompatibility;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class IrisRenderingPipelineMixin {
    @WrapWithCondition(method = "beginLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;allChanged()V"))
    private boolean glass$keepProjectionReloadLocal(LevelExtractor extractor) {
        return IrisCompatibility.shaderView() == null;
    }
}
