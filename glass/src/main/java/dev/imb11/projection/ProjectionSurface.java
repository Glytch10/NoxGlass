package dev.imb11.projection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

public final class ProjectionSurface {
    public static final int REVEAL_EDGE_WIDTH = 2;
    public static final int MAX_RADIUS = 64;
    public static final int MAX_FACES = 400;

    private static final double BOUNDS_INFLATION = 0.01D;
    private static final Comparator<FaceKey> FACE_KEY_COMPARATOR = Comparator
            .comparingInt((FaceKey key) -> key.position().getX())
            .thenComparingInt(key -> key.position().getY())
            .thenComparingInt(key -> key.position().getZ())
            .thenComparingInt(key -> key.normal().get3DDataValue());

    private final BlockPos origin;
    private final Direction facing;
    private final Direction uDirection;
    private final Direction vDirection;
    private final AABB renderBounds;
    private final List<Face> faces;
    private final Map<BlockPos, BlockSample> dependencies;
    private final int furthestRevealDistance;
    private final boolean truncated;

    private ProjectionSurface(
            BlockPos origin,
            Direction facing,
            Direction uDirection,
            Direction vDirection,
            AABB renderBounds,
            List<Face> faces,
            Map<BlockPos, BlockSample> dependencies,
            int furthestRevealDistance,
            boolean truncated
    ) {
        this.origin = origin;
        this.facing = facing;
        this.uDirection = uDirection;
        this.vDirection = vDirection;
        this.renderBounds = renderBounds;
        this.faces = faces;
        this.dependencies = dependencies;
        this.furthestRevealDistance = furthestRevealDistance;
        this.truncated = truncated;
    }

    public static ProjectionSurface rebuild(
            BlockPos origin,
            Direction facing,
            BlockQuery query
    ) {
        Objects.requireNonNull(origin);
        Objects.requireNonNull(facing);
        Objects.requireNonNull(query);

        BlockPos immutableOrigin = origin.immutable();
        FaceKey root = new FaceKey(immutableOrigin, facing);
        DependencyCollector dependencyCollector = new DependencyCollector(query);
        Map<FaceKey, Integer> distances = new HashMap<>();
        distances.put(root, 0);
        List<FaceKey> layer = List.of(root);
        boolean truncated = false;

        while (!layer.isEmpty()) {
            TreeSet<FaceKey> nextKeys = new TreeSet<>(FACE_KEY_COMPARATOR);
            for (FaceKey current : layer) {
                for (FaceKey candidate : expand(current, root, dependencyCollector)) {
                    if (distances.containsKey(candidate) || nextKeys.contains(candidate)) {
                        continue;
                    }
                    if (!withinRadius(immutableOrigin, candidate.position())) {
                        truncated = true;
                        continue;
                    }
                    nextKeys.add(candidate);
                }
            }

            if (nextKeys.isEmpty()) {
                break;
            }

            int remaining = MAX_FACES - distances.size();
            if (remaining <= 0) {
                truncated = true;
                break;
            }

            int nextDistance = distances.get(layer.getFirst()) + 1;
            List<FaceKey> nextLayer = new ArrayList<>(Math.min(remaining, nextKeys.size()));
            for (FaceKey nextKey : nextKeys) {
                if (nextLayer.size() >= remaining) {
                    truncated = true;
                    break;
                }
                distances.put(nextKey, nextDistance);
                nextLayer.add(nextKey);
            }
            layer = List.copyOf(nextLayer);
        }

        List<Map.Entry<FaceKey, Integer>> sortedEntries = new ArrayList<>(distances.entrySet());
        sortedEntries.sort(Map.Entry.comparingByKey(FACE_KEY_COMPARATOR));
        List<Face> faces = new ArrayList<>(sortedEntries.size());
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int furthestRevealDistance = 0;

        for (Map.Entry<FaceKey, Integer> entry : sortedEntries) {
            FaceKey key = entry.getKey();
            BlockPos position = key.position();
            Direction normal = key.normal();
            int revealDistance = entry.getValue();
            faces.add(new Face(
                    position,
                    normal,
                    uDirection(normal),
                    vDirection(normal),
                    revealDistance,
                    key.equals(root)
            ));
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX() + 1);
            maxY = Math.max(maxY, position.getY() + 1);
            maxZ = Math.max(maxZ, position.getZ() + 1);
            furthestRevealDistance = Math.max(furthestRevealDistance, revealDistance);
        }

        List<Face> immutableFaces = List.copyOf(faces);
        AABB renderBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(BOUNDS_INFLATION);
        return new ProjectionSurface(
                immutableOrigin,
                facing,
                uDirection(facing),
                vDirection(facing),
                renderBounds,
                immutableFaces,
                dependencyCollector.snapshot(),
                furthestRevealDistance,
                truncated
        );
    }

    public boolean isTopologyValid(Direction facing, BlockQuery query) {
        Objects.requireNonNull(facing);
        Objects.requireNonNull(query);
        if (this.facing != facing) {
            return false;
        }
        for (Map.Entry<BlockPos, BlockSample> dependency : dependencies.entrySet()) {
            if (Objects.requireNonNull(query.sample(dependency.getKey())) != dependency.getValue()) {
                return false;
            }
        }
        return true;
    }

    public BlockPos origin() {
        return origin;
    }

    public Direction facing() {
        return facing;
    }

    public Direction uDirection() {
        return uDirection;
    }

    public Direction vDirection() {
        return vDirection;
    }

    public AABB renderBounds() {
        return renderBounds;
    }

    public List<Face> faces() {
        return faces;
    }

    public int furthestRevealDistance() {
        return furthestRevealDistance;
    }

    public int completedRevealDistance() {
        return furthestRevealDistance + REVEAL_EDGE_WIDTH;
    }

    public boolean truncated() {
        return truncated;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ProjectionSurface surface
                && origin.equals(surface.origin)
                && facing == surface.facing
                && truncated == surface.truncated
                && faces.equals(surface.faces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(origin, facing, truncated, faces);
    }

    private static List<FaceKey> expand(
            FaceKey current,
            FaceKey root,
            DependencyCollector dependencies
    ) {
        List<FaceKey> candidates = new ArrayList<>(4);
        BlockPos position = current.position();
        Direction normal = current.normal();
        Direction[] edgeDirections = edgeDirections(normal);

        if (current.equals(root)) {
            for (Direction edgeDirection : edgeDirections) {
                BlockPos sidePosition = position.relative(edgeDirection);
                if (dependencies.sample(sidePosition) != BlockSample.GLASS) {
                    continue;
                }
                BlockPos diagonalPosition = sidePosition.relative(normal);
                if (dependencies.sample(diagonalPosition) == BlockSample.OTHER) {
                    candidates.add(new FaceKey(sidePosition, normal));
                }
            }
            return candidates;
        }

        if (dependencies.sample(position) != BlockSample.GLASS
                || dependencies.sample(position.relative(normal)) != BlockSample.OTHER) {
            return candidates;
        }

        for (Direction edgeDirection : edgeDirections) {
            BlockPos sidePosition = position.relative(edgeDirection);
            BlockSample side = dependencies.sample(sidePosition);
            if (side == BlockSample.GLASS) {
                BlockPos diagonalPosition = sidePosition.relative(normal);
                BlockSample diagonal = dependencies.sample(diagonalPosition);
                if (diagonal == BlockSample.OTHER) {
                    candidates.add(new FaceKey(sidePosition, normal));
                } else if (diagonal == BlockSample.GLASS) {
                    candidates.add(new FaceKey(diagonalPosition, edgeDirection.getOpposite()));
                }
            } else if (side == BlockSample.OTHER) {
                BlockPos diagonalPosition = sidePosition.relative(normal);
                if (dependencies.sample(diagonalPosition) == BlockSample.OTHER
                        && dependencies.sample(position.relative(normal.getOpposite())) == BlockSample.GLASS) {
                    candidates.add(new FaceKey(position, edgeDirection));
                }
            }
        }
        return candidates;
    }

    private static Direction[] edgeDirections(Direction normal) {
        Direction uDirection = uDirection(normal);
        Direction vDirection = vDirection(normal);
        return new Direction[]{
                uDirection,
                uDirection.getOpposite(),
                vDirection,
                vDirection.getOpposite()
        };
    }

    private static boolean withinRadius(BlockPos origin, BlockPos position) {
        return Math.abs(position.getX() - origin.getX()) <= MAX_RADIUS
                && Math.abs(position.getY() - origin.getY()) <= MAX_RADIUS
                && Math.abs(position.getZ() - origin.getZ()) <= MAX_RADIUS;
    }

    private static Direction uDirection(Direction facing) {
        return switch (facing) {
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            case SOUTH, DOWN, UP -> Direction.EAST;
            case NORTH -> Direction.WEST;
        };
    }

    private static Direction vDirection(Direction facing) {
        return switch (facing) {
            case WEST, EAST, SOUTH, NORTH -> Direction.UP;
            case DOWN -> Direction.SOUTH;
            case UP -> Direction.NORTH;
        };
    }

    @FunctionalInterface
    public interface BlockQuery {
        BlockSample sample(BlockPos position);
    }

    public enum BlockSample {
        GLASS,
        OTHER,
        UNLOADED
    }

    public record Face(
            BlockPos position,
            Direction normal,
            Direction uDirection,
            Direction vDirection,
            int revealDistance,
            boolean seed
    ) {
        public Face {
            position = position.immutable();
            Objects.requireNonNull(normal);
            Objects.requireNonNull(uDirection);
            Objects.requireNonNull(vDirection);
        }
    }

    private record FaceKey(BlockPos position, Direction normal) {
        private FaceKey {
            position = position.immutable();
            Objects.requireNonNull(normal);
        }
    }

    private static final class DependencyCollector {
        private final BlockQuery query;
        private final Map<BlockPos, BlockSample> dependencies = new HashMap<>();

        private DependencyCollector(BlockQuery query) {
            this.query = query;
        }

        private BlockSample sample(BlockPos position) {
            BlockPos immutablePosition = position.immutable();
            BlockSample existing = dependencies.get(immutablePosition);
            if (existing != null) {
                return existing;
            }
            BlockSample sampled = Objects.requireNonNull(query.sample(immutablePosition));
            dependencies.put(immutablePosition, sampled);
            return sampled;
        }

        private Map<BlockPos, BlockSample> snapshot() {
            return Map.copyOf(dependencies);
        }
    }
}
