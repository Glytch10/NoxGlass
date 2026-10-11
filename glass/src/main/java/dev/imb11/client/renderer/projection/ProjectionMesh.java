package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.imb11.client.compat.IrisCompatibility;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

public final class ProjectionMesh implements AutoCloseable {
    private static final ByteBufferBuilder BUILDER_BUFFER = new ByteBufferBuilder(786432);
    private final GpuBuffer vertices;
    private final int indexCount;

    public static BufferBuilder begin(VertexFormat format) {
        return new BufferBuilder(BUILDER_BUFFER, PrimitiveTopology.QUADS, format);
    }

    public ProjectionMesh(MeshData mesh) {
        try (mesh) {
            vertices = RenderSystem.getDevice().createBuffer(() -> "GLASS mesh", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            indexCount = mesh.drawState().indexCount();
        }
    }

    public void draw(RenderPipeline pipeline, Matrix4f pose, Map<String, GpuTextureView> textures, GpuBuffer parameters) {
        draw(pipeline, pose, textures, Map.of(), parameters);
    }

    public void draw(RenderPipeline pipeline, Matrix4f pose, Map<String, GpuTextureView> textures, Map<String, GpuSampler> samplers,
                     GpuBuffer parameters) {
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        var indexBuffer = indices.getBuffer(indexCount);
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(pose, new Vector4f(1), new Vector3f(), new Matrix4f());
        try (var ignored = IrisCompatibility.preserveSamplers(textures.size());
             var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "GLASS projection surface",
                target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            if (parameters != null) {
                pass.setUniform("SurfaceParameters", parameters);
            }
            textures.forEach((name, texture) -> pass.bindTexture(name, texture,
                    samplers.getOrDefault(name, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))));
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(indexCount, 1, 0, 0, 0);
        }
    }

    @Override
    public void close() {
        vertices.close();
    }
}
