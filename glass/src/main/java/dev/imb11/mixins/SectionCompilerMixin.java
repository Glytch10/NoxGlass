package dev.imb11.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.imb11.client.compat.IrisCompatibility;
import dev.imb11.client.renderer.projection.ProjectionRenderRegion;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results glass$compileWithVanillaVertices(SectionPos sectionPos, RenderSectionRegion region,
                                                                     VertexSorting vertexSorting, SectionBufferBuilderPack builders,
                                                                     Operation<SectionCompiler.Results> original) {
        if (!((ProjectionRenderRegion) region).glass$isProjection()) {
            return original.call(sectionPos, region, vertexSorting, builders);
        }
        return IrisCompatibility.withVanillaVertices(() -> original.call(sectionPos, region, vertexSorting, builders));
    }
}
