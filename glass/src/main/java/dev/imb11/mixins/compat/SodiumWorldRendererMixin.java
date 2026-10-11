package dev.imb11.mixins.compat;

import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.compat.ProjectionSodiumRenderer;
import dev.imb11.client.remote.ProjectionChunkStorage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class SodiumWorldRendererMixin implements ProjectionSodiumRenderer {
    @Shadow private ClientLevel level;
    @Shadow private RenderSectionManager renderSectionManager;
    @Unique private int glass$projectionDistance;
    @Unique private final LongOpenHashSet glass$loadedChunks = new LongOpenHashSet();

    @Override
    public void glass$setProjectionDistance(int distance) {
        glass$projectionDistance = distance;
    }

    @WrapOperation(method = {"initRenderer", "setupTerrain"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
    private int glass$projectionDistance(Options options, Operation<Integer> original) {
        return glass$projectionDistance > 0 ? glass$projectionDistance : original.call(options);
    }

    @Unique
    private LongOpenHashSet glass$readyProjectionChunks() {
        var ready = new LongOpenHashSet();
        var camera = ProjectionRenderContext.camera();
        if (camera != null) {
            var center = ChunkPos.containing(net.minecraft.core.BlockPos.containing(camera.position()));
            ProjectionChunkStorage.of(level).forEachReadyChunk(center, glass$projectionDistance + 1, chunk -> ready.add(chunk));
        }
        return ready;
    }

    @WrapOperation(method = "initRenderer", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;getReadyChunks()Lit/unimi/dsi/fastutil/longs/LongCollection;"))
    private LongCollection glass$projectionChunks(ChunkTracker tracker, Operation<LongCollection> original) {
        return glass$projectionDistance > 0 ? glass$readyProjectionChunks() : original.call(tracker);
    }

    @Inject(method = "initRenderer", at = @At("TAIL"))
    private void glass$trackProjectionChunks(CallbackInfo callbackInfo) {
        if (glass$projectionDistance > 0) {
            glass$loadedChunks.clear();
            glass$loadedChunks.addAll(glass$readyProjectionChunks());
        }
    }

    @Inject(method = "processChunkEvents", at = @At("HEAD"), cancellable = true)
    private void glass$independentChunkEvents(CallbackInfo callbackInfo) {
        if (glass$projectionDistance <= 0) {
            return;
        }
        callbackInfo.cancel();
        renderSectionManager.beforeSectionUpdates();
        var ready = glass$readyProjectionChunks();
        var removed = glass$loadedChunks.iterator();
        while (removed.hasNext()) {
            long chunk = removed.nextLong();
            if (!ready.contains(chunk)) {
                renderSectionManager.onChunkRemoved(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
                removed.remove();
            }
        }
        for (long chunk : ready) {
            if (glass$loadedChunks.add(chunk)) {
                renderSectionManager.onChunkAdded(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            }
        }
    }
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void glass$projectionEntityVisibility(net.minecraft.client.renderer.entity.EntityRenderer<?, ?> renderer, Entity entity, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (ProjectionRenderContext.isActive()) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "scheduleRebuildForChunk", at = @At("TAIL"))
    private void glass$mirrorSectionRebuild(int x, int y, int z, boolean important, CallbackInfo callbackInfo) {
        if (glass$projectionDistance == 0 && (Object) this == SodiumWorldRenderer.instanceNullable()) {
            ProjectionRenderManager.onMainSectionDirty(x, y, z);
        }
    }
}
