package dev.imb11.mixins.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.imb11.client.compat.IrisCompatibility;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask", remap = false)
abstract class SodiumMeshingTaskMixin {
    @Unique private Object glass$worldSettings;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void glass$captureWorldSettings(CallbackInfo callbackInfo) {
        glass$worldSettings = IrisCompatibility.worldSettings();
    }

    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput glass$compileProjection(ChunkBuildContext context, CancellationToken cancellation,
                                                    Operation<ChunkBuildOutput> original) {
        try (var ignored = IrisCompatibility.useWorldSettings(glass$worldSettings)) {
            return original.call(context, cancellation);
        }
    }
}
