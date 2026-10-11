package dev.imb11.mixins.compat;

import dev.imb11.client.compat.IrisCompatibility;
import dev.imb11.client.renderer.projection.RenderResources;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
abstract class IrisShadowRendererMixin {
    @Shadow @Final private RenderBuffers buffers;
    @Shadow @Final private FeatureRenderDispatcher featureRenderDispatcher;
    @Unique private boolean glass$projection;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void glass$projectionResources(CallbackInfo callbackInfo) {
        glass$projection = IrisCompatibility.shaderView() != null;
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void glass$closeProjectionResources(CallbackInfo callbackInfo) {
        if (glass$projection) {
            glass$projection = false;
            featureRenderDispatcher.close();
            RenderResources.closeBufferBuilders(buffers);
            RenderResources.closeAvailablePoolBuffers(buffers.sectionBufferPool());
        }
    }
}
