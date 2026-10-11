package dev.imb11.mixins;

import dev.imb11.client.compat.IrisCompatibility;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
abstract class OptionsMixin {
    @Inject(method = "getEffectiveRenderDistance", at = @At("HEAD"), cancellable = true)
    private void glass$projectionRenderDistance(CallbackInfoReturnable<Integer> callbackInfo) {
        var view = IrisCompatibility.shaderView();
        if (view != null) {
            callbackInfo.setReturnValue(view.renderState().optionsRenderState.renderDistance);
        }
    }
}
