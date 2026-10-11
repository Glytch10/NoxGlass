package dev.imb11.mixins.compat;

import dev.imb11.client.compat.IrisCompatibility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
abstract class IrisCameraUniformsMixin {
    @Inject(method = "getEyeBrightness", at = @At("HEAD"), cancellable = true)
    private static void glass$projectionEyeBrightness(CallbackInfoReturnable<Vector2i> callbackInfo) {
        var view = IrisCompatibility.shaderView();
        if (view != null) {
            var level = Minecraft.getInstance().level;
            var position = BlockPos.containing(view.camera().position());
            callbackInfo.setReturnValue(new Vector2i(level.getBrightness(LightLayer.BLOCK, position) * 16,
                    level.getBrightness(LightLayer.SKY, position) * 16));
        }
    }
}
