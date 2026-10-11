package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import net.minecraft.client.renderer.BindGroupLayouts;
import dev.imb11.projection.ProjectionSurface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectionSurfaceRenderer {
    private static final float SURFACE_OFFSET = 0.002F;
    private static final int MAX_PROJECTIONS = 4;
    private static final int DISTANCE_RADIX = 4096;
    private static final float CROSSFADE_WIDTH = 4.0F;
    private static final String[] PROJECTION_SAMPLERS = {"Projection0", "Projection1", "Projection2", "Projection3"};
    private static final Identifier SHADER_LOCATION =
            Identifier.fromNamespaceAndPath("glass", "core/projection_surface");
    private static final Map<ClientLevel, Map<BlockPos, CachedMesh>> CACHED_MESHES = new IdentityHashMap<>();
    private static ClientLevel activeLevel;
    private static List<Projection> projections = List.of();
    private static final Map<FaceKey, SharedFace> SHARED_FACES = new HashMap<>();
    private static final Map<BlockPos, List<Projection>> GROUPS = new HashMap<>();
    private static final Map<EdgeKey, EnumSet<Direction>> SHARED_EDGE_NORMALS = new HashMap<>();
    private static long layoutVersion;
    private static final RenderPipeline SURFACE_PIPELINE = pipeline(false);
    private static final RenderPipeline LOADING_PIPELINE = pipeline(true);

    private ProjectionSurfaceRenderer() {
    }

    private static RenderPipeline pipeline(boolean loading) {
        var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("glass", loading ? "projection_loading" : "projection_surface"))
                .withVertexShader(SHADER_LOCATION).withFragmentShader(SHADER_LOCATION)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, !loading))
                .withCull(false);
        var surfaceLayout = BindGroupLayout.builder().withUniform("SurfaceParameters", UniformType.UNIFORM_BUFFER);
        for (String sampler : PROJECTION_SAMPLERS) {
            surfaceLayout.withSampler(sampler);
        }
        builder.withBindGroupLayout(surfaceLayout.build());
        if (loading) {
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        }
        return builder.build();
    }

    public static void prepare(ClientLevel level, List<Projection> visibleProjections) {
        RenderSystem.assertOnRenderThread();
        if (activeLevel != level) {
            resetNow();
            activeLevel = level;
        }
        List<Projection> next = visibleProjections.stream()
                .sorted(Comparator.comparingLong(projection -> projection.projectorPos().asLong()))
                .toList();
        boolean changed = projections.size() != next.size();
        for (int index = 0; !changed && index < next.size(); index++) {
            Projection previous = projections.get(index);
            Projection replacement = next.get(index);
            changed = !previous.projectorPos().equals(replacement.projectorPos())
                    || !previous.surface().equals(replacement.surface());
        }
        projections = next;
        if (!changed) {
            return;
        }

        layoutVersion++;
        SHARED_FACES.clear();
        SHARED_EDGE_NORMALS.clear();
        GROUPS.clear();
        for (List<Projection> group : blendGroups(projections)) {
            for (int index = 0; index < group.size(); index++) {
                Projection projection = group.get(index);
                GROUPS.put(projection.projectorPos(), group);
                for (ProjectionSurface.Face face : projection.surface().faces()) {
                    FaceKey key = new FaceKey(face.position(), face.normal());
                    SharedFace shared = SHARED_FACES.get(key);
                    if (shared == null) {
                        shared = new SharedFace(projection.projectorPos());
                        SHARED_FACES.put(key, shared);
                        addEdgeNormals(geometry(face), SHARED_EDGE_NORMALS);
                    }
                    if (face.revealDistance() < 0 || face.revealDistance() >= DISTANCE_RADIX) {
                        throw new IllegalArgumentException("Projection reveal distance is outside the 12-bit mesh range");
                    }
                    shared.distances[index] = face.revealDistance();
                }
            }
        }
    }

    private static List<List<Projection>> blendGroups(List<Projection> projections) {
        int[] roots = new int[projections.size()];
        for (int index = 0; index < roots.length; index++) {
            roots[index] = index;
        }
        Map<FaceKey, Integer> firstOwners = new HashMap<>();
        for (int index = 0; index < projections.size(); index++) {
            for (ProjectionSurface.Face face : projections.get(index).surface().faces()) {
                Integer owner = firstOwners.putIfAbsent(new FaceKey(face.position(), face.normal()), index);
                if (owner != null) {
                    roots[root(roots, index)] = root(roots, owner);
                }
            }
        }
        Map<Integer, List<Projection>> groups = new LinkedHashMap<>();
        for (int index = 0; index < projections.size(); index++) {
            List<Projection> group = groups.computeIfAbsent(root(roots, index), ignored -> new ArrayList<>());
            if (group.size() < MAX_PROJECTIONS) {
                group.add(projections.get(index));
            }
        }
        return List.copyOf(groups.values());
    }

    private static int root(int[] roots, int index) {
        while (roots[index] != index) {
            roots[index] = roots[roots[index]];
            index = roots[index];
        }
        return index;
    }

    public static void render(ClientLevel level, BlockPos projectorPos, ProjectionSurface surface,
                              @Nullable ProjectionRenderManager.ProjectionFeed feed, PoseStack matrices, float loadingOpacity) {
        boolean loading = loadingOpacity >= 0.0F;
        if (!loading && !ProjectionRenderManager.isReady(feed)) {
            return;
        }
        CachedMesh mesh = getOrBuild(level, projectorPos, surface, loading);
        if (mesh == null) {
            return;
        }
        float[] reveals = {-1, -1, -1, -1};
        float[] channels = {0, 1, 2, 3};
        float[] positions = new float[16];
        Map<String, GpuTextureView> textures = new HashMap<>();
        GpuTextureView fallback = feed == null ? null : feed.colorTextureView();
        if (fallback == null) {
            fallback = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        }
        List<Projection> group = GROUPS.getOrDefault(projectorPos, List.of());
        for (int index = 0; index < MAX_PROJECTIONS; index++) {
            Projection projection = index < group.size() ? group.get(index) : null;
            textures.put(PROJECTION_SAMPLERS[index], projection == null ? fallback : projection.feed().colorTextureView());
            if (projection != null) {
                for (int previous = 0; previous < index; previous++) {
                    if (group.get(previous).feed().source().equals(projection.feed().source())) {
                        channels[index] = channels[previous];
                        break;
                    }
                }
                if (!loading && (mesh.projectionMask & 1 << index) != 0) {
                    ProjectionRenderManager.markVisible(projection.feed());
                }
                reveals[index] = projection.revealProgress();
                BlockPos offset = projection.surface().origin().subtract(mesh.origin);
                Direction facing = projection.surface().facing();
                positions[index * 4] = offset.getX() + 0.5F + facing.getStepX() * 0.5F;
                positions[index * 4 + 1] = offset.getY() + 0.5F + facing.getStepY() * 0.5F;
                positions[index * 4 + 2] = offset.getZ() + 0.5F + facing.getStepZ() * 0.5F;
            }
        }
        BlockPos offset = mesh.origin.subtract(projectorPos);
        Matrix4f pose = new Matrix4f(matrices.last().pose()).translate(offset.getX(), offset.getY(), offset.getZ());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var data = stack.malloc(112);
            for (float value : reveals) data.putFloat(value);
            for (float value : channels) data.putFloat(value);
            for (float value : positions) data.putFloat(value);
            data.putFloat(loadingOpacity).putFloat(CROSSFADE_WIDTH).putFloat(0).putFloat(0).flip();
            try (GpuBuffer parameters = RenderSystem.getDevice().createBuffer(() -> "GLASS surface parameters", GpuBuffer.USAGE_UNIFORM, data)) {
                mesh.vertexBuffer.draw(loading ? LOADING_PIPELINE : SURFACE_PIPELINE, pose, textures, parameters);
            }
        }
    }

    public static void release(ClientLevel level, BlockPos projectorPos) {
        RenderResources.runOnRenderThread(() -> releaseNow(level, projectorPos));
    }

    public static void releaseLevel(ClientLevel level) {
        RenderResources.runOnRenderThread(() -> releaseLevelNow(level));
    }

    public static void reset() {
        RenderResources.runOnRenderThread(ProjectionSurfaceRenderer::resetNow);
    }

    private static CachedMesh getOrBuild(ClientLevel level, BlockPos projectorPos, ProjectionSurface surface, boolean loading) {
        if (activeLevel != level) {
            resetNow();
            activeLevel = level;
        }
        Map<BlockPos, CachedMesh> levelMeshes = CACHED_MESHES.computeIfAbsent(level, ignored -> new HashMap<>());
        BlockPos key = projectorPos.immutable();
        CachedMesh cachedMesh = levelMeshes.get(key);
        if (cachedMesh != null && cachedMesh.matches(surface, loading)) {
            return cachedMesh.vertexBuffer == null ? null : cachedMesh;
        }

        CachedMesh replacement = build(projectorPos, surface, loading);
        levelMeshes.put(key, replacement);
        if (cachedMesh != null) {
            cachedMesh.close();
        }
        return replacement.vertexBuffer == null ? null : replacement;
    }

    private static CachedMesh build(BlockPos projectorPos, ProjectionSurface surface, boolean loading) {
        List<FaceGeometry> faceGeometries = new ArrayList<>(surface.faces().size());
        Map<EdgeKey, EnumSet<Direction>> edgeNormals = loading ? new HashMap<>() : SHARED_EDGE_NORMALS;
        int projectionMask = 0;
        for (ProjectionSurface.Face face : surface.faces()) {
            FaceGeometry geometry = geometry(face);
            SharedFace shared = SHARED_FACES.get(new FaceKey(face.position(), face.normal()));
            if (loading) {
                addEdgeNormals(geometry, edgeNormals);
            }
            if (loading || shared != null && shared.owner.equals(projectorPos)) {
                faceGeometries.add(geometry);
                if (shared != null) {
                    for (int index = 0; index < MAX_PROJECTIONS; index++) {
                        if (shared.distances[index] >= 0) {
                            projectionMask |= 1 << index;
                        }
                    }
                }
            }
        }

        if (faceGeometries.isEmpty()) {
            return new CachedMesh(surface, loading, projectionMask, null);
        }
        BufferBuilder builder = ProjectionMesh.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
        for (FaceGeometry geometry : faceGeometries) {
            emitFace(builder, surface.origin(), geometry, edgeNormals);
        }

        ProjectionMesh vertexBuffer = new ProjectionMesh(builder.buildOrThrow());
        return new CachedMesh(surface, loading, projectionMask, vertexBuffer);
    }

    private static void addEdgeNormals(FaceGeometry geometry, Map<EdgeKey, EnumSet<Direction>> edgeNormals) {
        for (EdgeKey edge : geometry.edges()) {
            edgeNormals.computeIfAbsent(edge, ignored -> EnumSet.noneOf(Direction.class))
                    .add(geometry.face().normal());
        }
    }

    private static FaceGeometry geometry(ProjectionSurface.Face face) {
        BlockPos position = face.position();
        IntegerCorner[] corners = {
                corner(position, face.normal(), face.uDirection(), face.vDirection(), false, false),
                corner(position, face.normal(), face.uDirection(), face.vDirection(), true, false),
                corner(position, face.normal(), face.uDirection(), face.vDirection(), true, true),
                corner(position, face.normal(), face.uDirection(), face.vDirection(), false, true)
        };
        EdgeKey[] edges = {
                new EdgeKey(corners[0], corners[1]),
                new EdgeKey(corners[1], corners[2]),
                new EdgeKey(corners[2], corners[3]),
                new EdgeKey(corners[3], corners[0])
        };
        return new FaceGeometry(face, corners, edges);
    }

    private static IntegerCorner corner(
            BlockPos position,
            Direction normal,
            Direction uDirection,
            Direction vDirection,
            boolean highU,
            boolean highV
    ) {
        int x = position.getX() + Math.max(0, normal.getStepX());
        int y = position.getY() + Math.max(0, normal.getStepY());
        int z = position.getZ() + Math.max(0, normal.getStepZ());
        int uOffset = cornerOffset(uDirection, highU);
        int vOffset = cornerOffset(vDirection, highV);
        x += Math.abs(uDirection.getStepX()) * uOffset + Math.abs(vDirection.getStepX()) * vOffset;
        y += Math.abs(uDirection.getStepY()) * uOffset + Math.abs(vDirection.getStepY()) * vOffset;
        z += Math.abs(uDirection.getStepZ()) * uOffset + Math.abs(vDirection.getStepZ()) * vOffset;
        return new IntegerCorner(x, y, z);
    }

    private static void emitFace(
            VertexConsumer vertices,
            BlockPos origin,
            FaceGeometry geometry,
            Map<EdgeKey, EnumSet<Direction>> edgeNormals
    ) {
        ProjectionSurface.Face face = geometry.face();
        SharedFace shared = SHARED_FACES.get(new FaceKey(face.position(), face.normal()));
        int[] distances = shared == null ? new int[]{-1, -1, -1, -1} : shared.distances;
        float u = Math.max(0, distances[0]) + Math.max(0, distances[1]) * DISTANCE_RADIX;
        float v = Math.max(0, distances[2]) + Math.max(0, distances[3]) * DISTANCE_RADIX;
        for (int index = 0; index < geometry.corners().length; index++) {
            EdgeKey previousEdge = geometry.edges()[(index + geometry.edges().length - 1) % geometry.edges().length];
            EdgeKey nextEdge = geometry.edges()[index];
            emitVertex(
                    vertices,
                    origin,
                    geometry.corners()[index],
                    face.normal(),
                    edgeNormals.get(previousEdge),
                    edgeNormals.get(nextEdge),
                    u,
                    v,
                    distances[0] >= 0 ? 255 : 0,
                    distances[1] >= 0 ? 255 : 0,
                    distances[2] >= 0 ? 255 : 0,
                    distances[3] >= 0 ? 255 : 0
            );
        }
    }

    private static void emitVertex(
            VertexConsumer vertices,
            BlockPos origin,
            IntegerCorner corner,
            Direction normal,
            EnumSet<Direction> previousEdgeNormals,
            EnumSet<Direction> nextEdgeNormals,
            float u,
            float v,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        EnumSet<Direction> miterNormals = EnumSet.of(normal);
        if (previousEdgeNormals != null) {
            miterNormals.addAll(previousEdgeNormals);
        }
        if (nextEdgeNormals != null) {
            miterNormals.addAll(nextEdgeNormals);
        }
        float x = corner.x() - origin.getX();
        float y = corner.y() - origin.getY();
        float z = corner.z() - origin.getZ();
        for (Direction miterNormal : miterNormals) {
            x += miterNormal.getStepX() * SURFACE_OFFSET;
            y += miterNormal.getStepY() * SURFACE_OFFSET;
            z += miterNormal.getStepZ() * SURFACE_OFFSET;
        }
        vertices.addVertex(x, y, z)
                .setUv(u, v)
                .setColor(red, green, blue, alpha);
    }

    private static int cornerOffset(Direction direction, boolean high) {
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return positive == high ? 1 : 0;
    }

    private static void releaseNow(ClientLevel level, BlockPos projectorPos) {
        Map<BlockPos, CachedMesh> levelMeshes = CACHED_MESHES.get(level);
        if (levelMeshes == null) {
            return;
        }
        CachedMesh cachedMesh = levelMeshes.remove(projectorPos);
        if (cachedMesh != null) {
            cachedMesh.close();
        }
        if (levelMeshes.isEmpty()) {
            CACHED_MESHES.remove(level);
        }
    }

    private static void releaseLevelNow(ClientLevel level) {
        Map<BlockPos, CachedMesh> levelMeshes = CACHED_MESHES.remove(level);
        if (levelMeshes != null) {
            levelMeshes.values().forEach(CachedMesh::close);
        }
        if (activeLevel == level) {
            activeLevel = null;
        }
    }

    private static void resetNow() {
        CACHED_MESHES.values().forEach(levelMeshes -> levelMeshes.values().forEach(CachedMesh::close));
        CACHED_MESHES.clear();
        projections = List.of();
        SHARED_FACES.clear();
        GROUPS.clear();
        SHARED_EDGE_NORMALS.clear();
        layoutVersion++;
        activeLevel = null;
    }

    private static final class CachedMesh implements AutoCloseable {
        private final ProjectionSurface surface;
        private final BlockPos origin;
        private final long layoutVersion;
        private final boolean loading;
        private final int projectionMask;
        private final ProjectionMesh vertexBuffer;

        private CachedMesh(
                ProjectionSurface surface,
                boolean loading,
                int projectionMask,
                ProjectionMesh vertexBuffer
        ) {
            this.surface = surface;
            this.origin = surface.origin().immutable();
            this.layoutVersion = ProjectionSurfaceRenderer.layoutVersion;
            this.loading = loading;
            this.projectionMask = projectionMask;
            this.vertexBuffer = vertexBuffer;
        }

        private boolean matches(ProjectionSurface surface, boolean loading) {
            return layoutVersion == ProjectionSurfaceRenderer.layoutVersion
                    && this.loading == loading
                    && this.surface.equals(surface);
        }

        @Override
        public void close() {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
        }
    }

    public record Projection(
            BlockPos projectorPos,
            ProjectionSurface surface,
            ProjectionRenderManager.ProjectionFeed feed,
            float revealProgress
    ) {
    }

    private record FaceKey(BlockPos position, Direction normal) {
    }

    private static final class SharedFace {
        private final BlockPos owner;
        private final int[] distances = new int[MAX_PROJECTIONS];

        private SharedFace(BlockPos owner) {
            this.owner = owner;
            Arrays.fill(distances, -1);
        }
    }

    private record FaceGeometry(
            ProjectionSurface.Face face,
            IntegerCorner[] corners,
            EdgeKey[] edges
    ) {
    }

    private record IntegerCorner(int x, int y, int z) implements Comparable<IntegerCorner> {
        @Override
        public int compareTo(IntegerCorner other) {
            int xComparison = Integer.compare(x, other.x);
            if (xComparison != 0) {
                return xComparison;
            }
            int yComparison = Integer.compare(y, other.y);
            return yComparison != 0 ? yComparison : Integer.compare(z, other.z);
        }
    }

    private record EdgeKey(IntegerCorner first, IntegerCorner second) {
        private EdgeKey {
            if (first.compareTo(second) > 0) {
                IntegerCorner swap = first;
                first = second;
                second = swap;
            }
        }
    }
}
