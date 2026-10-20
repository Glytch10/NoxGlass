package dev.imb11.client.renderer.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.imb11.blocks.TerminalBlock;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.items.GItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class TerminalBlockEntityRenderer implements BlockEntityRenderer<TerminalBlockEntity> {
    private BakedModel bakedModel;
    private final List<BakedQuad> frame = new ArrayList<>();
    private final List<BakedQuad> eye = new ArrayList<>();
    private final Vector3f eyeCenter = new Vector3f();

    public TerminalBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(TerminalBlockEntity entity, float tickDelta, PoseStack matrices,
                       MultiBufferSource buffers, int light, int overlay) {
        BakedModel model = Minecraft.getInstance().getItemRenderer().getItemModelShaper().getItemModel(GItems.TERMINAL);
        if (model != bakedModel) {
            rebuild(model);
        }
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        matrices.pushPose();
        matrices.translate(0.5D, 0.5D, 0.5D);
        switch (entity.getBlockState().getValue(TerminalBlock.FACING)) {
            case NORTH -> matrices.mulPose(Axis.YP.rotationDegrees(-180.0F));
            case WEST -> matrices.mulPose(Axis.YP.rotationDegrees(-90.0F));
            case EAST -> matrices.mulPose(Axis.YP.rotationDegrees(-270.0F));
            case UP -> matrices.mulPose(Axis.XP.rotationDegrees(-90.0F));
            case DOWN -> {
                matrices.mulPose(Axis.YP.rotationDegrees(-180.0F));
                matrices.mulPose(Axis.XP.rotationDegrees(-270.0F));
            }
            default -> {}
        }
        matrices.translate(-0.5D, -0.5D, -0.5D);
        renderQuads(frame, matrices, vertices, light, overlay);
        matrices.translate(eyeCenter.x(), eyeCenter.y(), eyeCenter.z());
        matrices.mulPose(entity.getEyeRotation(tickDelta));
        matrices.translate(-eyeCenter.x(), -eyeCenter.y(), -eyeCenter.z());
        renderQuads(eye, matrices, vertices, light, overlay);
        matrices.popPose();
    }

    private void rebuild(BakedModel model) {
        frame.clear();
        eye.clear();
        eyeCenter.zero();
        RandomSource random = RandomSource.create(42L);
        for (Direction face : Direction.values()) {
            random.setSeed(42L);
            addQuads(model.getQuads(null, face, random));
        }
        random.setSeed(42L);
        addQuads(model.getQuads(null, null, random));
        int vertexCount = 0;
        for (BakedQuad quad : eye) {
            int[] data = quad.getVertices();
            int stride = data.length / 4;
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * stride;
                eyeCenter.add(Float.intBitsToFloat(data[offset]), Float.intBitsToFloat(data[offset + 1]), Float.intBitsToFloat(data[offset + 2]));
                vertexCount++;
            }
        }
        if (vertexCount > 0) {
            eyeCenter.div(vertexCount);
        }
        bakedModel = model;
    }

    private void addQuads(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            (quad.getTintIndex() == 1 ? eye : frame).add(quad);
        }
    }

    private static void renderQuads(List<BakedQuad> quads, PoseStack matrices, VertexConsumer vertices, int light, int overlay) {
        for (BakedQuad quad : quads) {
            vertices.putBulkData(matrices.last(), quad, 1.0F, 1.0F, 1.0F, 1.0F, light, overlay);
        }
    }
}
