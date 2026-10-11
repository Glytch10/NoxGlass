package dev.imb11.mixins.compat;

import dev.imb11.client.compat.IrisCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder", remap = false)
abstract class SodiumChunkBuilderMixin {
    @Inject(method = "getThreadCount", at = @At("RETURN"), cancellable = true)
    private static void glass$projectionWorkerLimit(CallbackInfoReturnable<Integer> callbackInfo) {
        if (IrisCompatibility.shaderView() != null) {
            callbackInfo.setReturnValue(Math.min(2, callbackInfo.getReturnValue()));
        }
    }
}
