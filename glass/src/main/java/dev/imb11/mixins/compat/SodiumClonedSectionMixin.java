package dev.imb11.mixins.compat;

import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection", remap = false)
abstract class SodiumClonedSectionMixin {
    @Shadow @Final private SectionPos pos;
    @Shadow @Final @Mutable private PalettedContainerRO<BlockState> blockData;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void glass$hideProjectionSource(CallbackInfo callbackInfo) {
        var hidden = ProjectionRenderContext.hiddenTerrainBlock();
        if (hidden != null && blockData != null && pos.equals(SectionPos.of(hidden))) {
            var copy = blockData.copy();
            copy.getAndSetUnchecked(hidden.getX() & 15, hidden.getY() & 15, hidden.getZ() & 15, Blocks.AIR.defaultBlockState());
            blockData = copy;
        }
    }
}
