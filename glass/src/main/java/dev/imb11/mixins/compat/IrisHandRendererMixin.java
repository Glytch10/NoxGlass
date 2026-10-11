package dev.imb11.mixins.compat;

import dev.imb11.client.compat.IrisCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
abstract class IrisHandRendererMixin {
    @Inject(method = {"renderSolid", "renderTranslucent"}, at = @At("HEAD"), cancellable = true)
    private void glass$skipProjectionHand(CallbackInfo callbackInfo) {
        if (IrisCompatibility.shaderView() != null) {
            callbackInfo.cancel();
        }
    }
}
