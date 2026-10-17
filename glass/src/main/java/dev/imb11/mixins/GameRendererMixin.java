package dev.imb11.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.client.compat.IrisCompatibility;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.GameRenderState;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import dev.imb11.client.renderer.projection.ProjectionLightmap;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.llamalad7.mixinextras.sugar.Local;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "mainCamera", at = @At("HEAD"), cancellable = true)
    private void glass$shaderCamera(CallbackInfoReturnable<Camera> callbackInfo) {
        if (IrisCompatibility.shaderView() != null) {
            callbackInfo.setReturnValue(IrisCompatibility.shaderView().camera());
        }
    }

    @Inject(method = "gameRenderState", at = @At("HEAD"), cancellable = true)
    private void glass$shaderRenderState(CallbackInfoReturnable<GameRenderState> callbackInfo) {
        if (IrisCompatibility.shaderView() != null) {
            callbackInfo.setReturnValue(IrisCompatibility.shaderView().renderState());
        }
    }

    @Inject(method = {"lightmap", "levelLightmap"}, at = @At("HEAD"), cancellable = true)
    private void glass$projectionProjectionLightmap(CallbackInfoReturnable<GpuTextureView> callbackInfo) {
        ProjectionLightmap lightTexture = ProjectionRenderContext.lightTexture();
        if (lightTexture != null) {
            callbackInfo.setReturnValue(lightTexture.getTextureView());
        }
    }

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void glass$projectionMainTarget(CallbackInfoReturnable<RenderTarget> callbackInfo) {
        RenderTarget projectionTarget = ProjectionRenderContext.target();
        if (projectionTarget != null) {
            callbackInfo.setReturnValue(projectionTarget);
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", ordinal = 0))
    private void glass$renderProjectionBeforeMain(DeltaTracker deltaTracker, CallbackInfo callbackInfo, @Local Matrix4f projectionMatrix) {
        ProjectionRenderManager.renderBeforeMain((GameRenderer) (Object) this, deltaTracker, projectionMatrix);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", shift = At.Shift.AFTER))
    private void glass$renderProjectors(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        var state = ((GameRenderer) (Object) this).gameRenderState().levelRenderState.cameraRenderState;
        ProjectorBlockEntityRenderer.renderAll(new org.joml.Matrix4f(state.viewRotationMatrix));
    }

    @Inject(method = "resetData", at = @At("HEAD"))
    private void glass$resetProjectionRenderer(CallbackInfo callbackInfo) {
        ProjectionRenderManager.reset();
        ProjectionSurfaceRenderer.reset();
        ProjectorBlockEntityRenderer.reset();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void glass$closeProjectionRenderer(CallbackInfo callbackInfo) {
        ProjectionRenderManager.reset();
        ProjectionSurfaceRenderer.reset();
        ProjectorBlockEntityRenderer.reset();
        ProjectorBlockEntityRenderer.clearLoaded();
    }
}
