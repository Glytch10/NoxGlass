package dev.imb11.mixins.compat;

import dev.imb11.client.compat.IrisCompatibility;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
abstract class IrisPipelineManagerMixin {
    @Inject(method = "getPipelineNullable", at = @At("HEAD"), cancellable = true)
    private void glass$projectionPipeline(CallbackInfoReturnable<WorldRenderingPipeline> callbackInfo) {
        if (IrisCompatibility.shaderView() != null && !IrisCompatibility.isUsingVanillaVertices()) {
            callbackInfo.setReturnValue((WorldRenderingPipeline) IrisCompatibility.shaderView().pipeline());
        } else if (ProjectionRenderContext.isActive() || IrisCompatibility.isUsingVanillaVertices()) {
            callbackInfo.setReturnValue(null);
        }
    }

    @Inject(method = "getPipeline", at = @At("HEAD"), cancellable = true)
    private void glass$optionalProjectionPipeline(CallbackInfoReturnable<Optional<WorldRenderingPipeline>> callbackInfo) {
        if (IrisCompatibility.shaderView() != null && !IrisCompatibility.isUsingVanillaVertices()) {
            callbackInfo.setReturnValue(Optional.ofNullable((WorldRenderingPipeline) IrisCompatibility.shaderView().pipeline()));
        } else if (ProjectionRenderContext.isActive() || IrisCompatibility.isUsingVanillaVertices()) {
            callbackInfo.setReturnValue(Optional.empty());
        }
    }
}
