package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongConsumer;

public final class DestinationSafety {
    static final double HOME_HORIZONTAL_MARGIN = 0.5;
    static final double HOME_UPPER_MARGIN = 1.5;

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }
    record Footprint(int minX, int maxX, int minZ, int maxZ) { }
    record CellRange(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) { }
    record Cell(int x, int y, int z) { }
    record Prepared(ServerLevel level, Vec3 position, float yaw, float pitch) { }
    enum HomeFit { FITS, BLOCKED, UNSAFE }

    private DestinationSafety() { }

    static Bounds mountedHomeClearance(Bounds root) {
        return new Bounds(root.minX - HOME_HORIZONTAL_MARGIN, root.minY,
                root.minZ - HOME_HORIZONTAL_MARGIN, root.maxX + HOME_HORIZONTAL_MARGIN,
                root.maxY + HOME_UPPER_MARGIN, root.maxZ + HOME_HORIZONTAL_MARGIN);
    }

    static boolean blocksMountedHome(Bounds root, Bounds clearance, Bounds obstacle, boolean configuredBedPart) {
        if (intersects(root, obstacle)) return true;
        return !configuredBedPart && intersects(clearance, obstacle);
    }

    static Footprint footprint(double centerX, double centerZ, double width) {
        if (!(width > 0)) throw new IllegalArgumentException("width must be positive");
        double half = width / 2.0;
        return new Footprint(floor(centerX - half), floor(Math.nextDown(centerX + half)),
                floor(centerZ - half), floor(Math.nextDown(centerZ + half)));
    }

    static CellRange collisionOwnerCells(Bounds occupied) {
        return new CellRange(floor(occupied.minX) - 1, floor(Math.nextDown(occupied.maxX)) + 1,
                floor(occupied.minY) - 1, floor(Math.nextDown(occupied.maxY)) + 1,
                floor(occupied.minZ) - 1, floor(Math.nextDown(occupied.maxZ)) + 1);
    }

    static Set<Long> involvedChunks(CellRange cells) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int x = chunkCoordinate(cells.minX); x <= chunkCoordinate(cells.maxX); x++) {
            for (int z = chunkCoordinate(cells.minZ); z <= chunkCoordinate(cells.maxZ); z++) {
                chunks.add(chunkKey(x, z));
            }
        }
        return Set.copyOf(chunks);
    }

    static void preloadInvolvedChunks(CellRange cells, Set<Long> loadedChunks, LongConsumer loader) {
        for (long chunk : involvedChunks(cells)) {
            if (loadedChunks.add(chunk)) loader.accept(chunk);
        }
    }

    static boolean collisionFree(Bounds occupied, CellRange owners,
                                 Function<Cell, List<Bounds>> collisionShapes) {
        for (int x = owners.minX; x <= owners.maxX; x++) {
            for (int y = owners.minY; y <= owners.maxY; y++) {
                for (int z = owners.minZ; z <= owners.maxZ; z++) {
                    for (Bounds shape : collisionShapes.apply(new Cell(x, y, z))) {
                        if (intersects(occupied, shape)) return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean preloadAndCheckCollisions(Bounds occupied, Set<Long> loadedChunks,
                                             LongConsumer loader,
                                             Function<Cell, List<Bounds>> collisionShapes) {
        CellRange owners = collisionOwnerCells(occupied);
        preloadInvolvedChunks(owners, loadedChunks, loader);
        return collisionFree(occupied, owners, collisionShapes);
    }

    static boolean isHazard(String descriptionId) {
        return descriptionId.contains("fire") || descriptionId.contains("lava")
                || descriptionId.contains("magma") || descriptionId.contains("cactus")
                || descriptionId.contains("sweet_berry_bush") || descriptionId.contains("wither_rose")
                || descriptionId.contains("powder_snow");
    }

    static boolean isUnsafeHomeCell(boolean hasFluid, String descriptionId) {
        return hasFluid || isHazard(descriptionId);
    }

    static CellRange homeHazardCells(Bounds occupied) {
        return new CellRange(floor(occupied.minX), floor(Math.nextDown(occupied.maxX)),
                floor(occupied.minY) - 1, floor(Math.nextDown(occupied.maxY)),
                floor(occupied.minZ), floor(Math.nextDown(occupied.maxZ)));
    }

    static boolean unmountedHomeFits(Entity player, ServerLevel level, Vec3 position) {
        AABB occupiedBox = player.getBoundingBox().move(position.subtract(player.position()));
        Bounds occupied = bounds(occupiedBox);
        if (!withinBuildHeight(occupied, level.getMinY(), level.getMaxY())
                || !level.getWorldBorder().isWithinBounds(occupiedBox)) return false;

        CellRange checked = homeHazardCells(occupied);
        preloadInvolvedChunks(checked, new HashSet<>(), chunk -> loadChunk(level, chunk));
        return !containsHomeHazard(level, checked);
    }

    static HomeFit mountedHomeFit(Entity root, ServerLevel level, Vec3 position, BlockPos homeBlock) {
        AABB rootBox = root.getBoundingBox().move(position.subtract(root.position()));
        Bounds rootBounds = bounds(rootBox);
        Bounds clearance = mountedHomeClearance(rootBounds);
        AABB clearanceBox = box(clearance);
        if (!withinBuildHeight(rootBounds, level.getMinY(), level.getMaxY())
                || !level.getWorldBorder().isWithinBounds(rootBox)
                || !level.getWorldBorder().isWithinBounds(clearanceBox)) return HomeFit.BLOCKED;

        CellRange owners = collisionOwnerCells(clearance);
        preloadInvolvedChunks(owners, new HashSet<>(), chunk -> loadChunk(level, chunk));
        if (containsHomeHazard(level, homeHazardCells(rootBounds))) return HomeFit.UNSAFE;
        CollisionContext context = CollisionContext.of(root);
        for (int x = owners.minX; x <= owners.maxX; x++) {
            for (int y = owners.minY; y <= owners.maxY; y++) {
                for (int z = owners.minZ; z <= owners.maxZ; z++) {
                    BlockPos blockPos = new BlockPos(x, y, z);
                    var state = level.getBlockState(blockPos);
                    boolean bedPart = isConfiguredBedPart(level, blockPos, homeBlock);
                    for (AABB local : state.getCollisionShape(level, blockPos, context).toAabbs()) {
                        if (blocksMountedHome(rootBounds, clearance, bounds(local.move(blockPos)), bedPart)) {
                            return HomeFit.BLOCKED;
                        }
                    }
                }
            }
        }
        return HomeFit.FITS;
    }

    private static boolean containsHomeHazard(ServerLevel level, CellRange checked) {
        for (int x = checked.minX; x <= checked.maxX; x++) {
            for (int y = checked.minY; y <= checked.maxY; y++) {
                for (int z = checked.minZ; z <= checked.maxZ; z++) {
                    BlockPos blockPos = new BlockPos(x, y, z);
                    var state = level.getBlockState(blockPos);
                    if (isUnsafeHomeCell(!state.getFluidState().isEmpty(),
                            state.getBlock().getDescriptionId())) return true;
                }
            }
        }
        return false;
    }

    static boolean spawnFits(ServerLevel level, BlockPos feet, int width, int height, Set<Long> loadedChunks) {
        double centerOffset = width % 2 == 0 ? 0.0 : 0.5;
        Footprint footprint = footprint(feet.getX() + centerOffset, feet.getZ() + centerOffset, width);
        Bounds occupied = new Bounds(footprint.minX, feet.getY(), footprint.minZ,
                footprint.maxX + 1.0, feet.getY() + height, footprint.maxZ + 1.0);
        AABB occupiedBox = box(occupied);
        if (!withinBuildHeight(occupied, level.getMinY(), level.getMaxY())
                || !level.getWorldBorder().isWithinBounds(occupiedBox)) return false;

        if (!preloadAndCheckCollisions(occupied, loadedChunks, chunk -> loadChunk(level, chunk),
                cell -> collisionShapes(level, cell, CollisionContext.empty()))) return false;
        for (int x = footprint.minX; x <= footprint.maxX; x++) {
            for (int z = footprint.minZ; z <= footprint.maxZ; z++) {
                BlockPos supportPos = new BlockPos(x, feet.getY() - 1, z);
                var support = level.getBlockState(supportPos);
                if (!support.getFluidState().isEmpty()
                        || !support.isCollisionShapeFullBlock(level, supportPos)
                        || isHazard(support.getBlock().getDescriptionId())) return false;
                for (int y = 0; y < height; y++) {
                    BlockPos occupiedPos = new BlockPos(x, feet.getY() + y, z);
                    var state = level.getBlockState(occupiedPos);
                    if (!state.getFluidState().isEmpty()
                            || isHazard(state.getBlock().getDescriptionId())) return false;
                }
            }
        }
        return true;
    }

    static boolean endFits(ServerLevel level, Entity root, Vec3 position, BlockPos platformAnchor,
                           Set<Long> loadedChunks) {
        AABB occupiedBox = root.getBoundingBox().move(position.subtract(root.position()));
        Bounds occupied = bounds(occupiedBox);
        if (!withinBuildHeight(occupied, level.getMinY(), level.getMaxY())
                || !level.getWorldBorder().isWithinBounds(occupiedBox)) return false;

        CollisionContext context = CollisionContext.of(root);
        if (!preloadAndCheckCollisions(occupied, loadedChunks, chunk -> loadChunk(level, chunk),
                cell -> simulatedEndCollisionShapes(level, cell, platformAnchor, context))) return false;
        int supportY = floor(occupied.minY) - 1;
        for (int x = floor(occupied.minX); x <= floor(Math.nextDown(occupied.maxX)); x++) {
            for (int z = floor(occupied.minZ); z <= floor(Math.nextDown(occupied.maxZ)); z++) {
                if (!isSimulatedPlatformObsidian(new Cell(x, supportY, z), platformAnchor)) return false;
            }
        }
        return true;
    }

    static void loadDestinationChunks(ServerLevel level, Vec3 position) {
        for (long chunk : Set.copyOf(destinationChunks(position.x, position.z))) loadChunk(level, chunk);
    }

    static List<Long> destinationChunks(double feetX, double feetZ) {
        int centerX = chunkCoordinate(floor(feetX));
        int centerZ = chunkCoordinate(floor(feetZ));
        List<Long> chunks = new ArrayList<>(25);
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) chunks.add(chunkKey(x, z));
        }
        return List.copyOf(chunks);
    }

    static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    static boolean withinBuildHeight(Bounds box, int minY, int maxY) {
        return box.minY >= minY && box.maxY <= (double) maxY + 1.0;
    }

    private static int chunkCoordinate(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, 16);
    }

    private static void loadChunk(ServerLevel level, long chunk) {
        level.getChunk((int) (chunk >> 32), (int) chunk);
    }

    private static List<Bounds> simulatedEndCollisionShapes(ServerLevel level, Cell cell,
                                                             BlockPos anchor, CollisionContext context) {
        if (isWithinPlatformColumns(cell, anchor)) {
            if (cell.y == anchor.getY() - 1) {
                return List.of(new Bounds(cell.x, cell.y, cell.z, cell.x + 1, cell.y + 1, cell.z + 1));
            }
            if (cell.y >= anchor.getY() && cell.y <= anchor.getY() + 2) return List.of();
        }
        return collisionShapes(level, cell, context);
    }

    private static List<Bounds> collisionShapes(ServerLevel level, Cell cell, CollisionContext context) {
        BlockPos position = new BlockPos(cell.x, cell.y, cell.z);
        List<Bounds> shapes = new ArrayList<>();
        for (AABB local : level.getBlockState(position).getCollisionShape(level, position, context).toAabbs()) {
            shapes.add(bounds(local.move(position)));
        }
        return List.copyOf(shapes);
    }

    private static boolean isSimulatedPlatformObsidian(Cell cell, BlockPos anchor) {
        return cell.y == anchor.getY() - 1 && isWithinPlatformColumns(cell, anchor);
    }

    private static boolean isWithinPlatformColumns(Cell cell, BlockPos anchor) {
        return Math.abs(cell.x - anchor.getX()) <= 2 && Math.abs(cell.z - anchor.getZ()) <= 2;
    }

    private static boolean isConfiguredBedPart(ServerLevel level, BlockPos candidate, BlockPos homeBlock) {
        var homeState = level.getBlockState(homeBlock);
        if (!(homeState.getBlock() instanceof BedBlock)) return false;
        if (candidate.equals(homeBlock)) return true;
        BlockPos other = homeBlock.relative(BedBlock.getConnectedDirection(homeState));
        return candidate.equals(other) && level.getBlockState(other).getBlock() instanceof BedBlock;
    }

    private static Bounds bounds(AABB box) {
        return new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static AABB box(Bounds bounds) {
        return new AABB(bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    private static boolean intersects(Bounds a, Bounds b) {
        return b.maxX > a.minX && b.minX < a.maxX && b.maxY > a.minY && b.minY < a.maxY
                && b.maxZ > a.minZ && b.minZ < a.maxZ;
    }

    private static int floor(double value) { return (int) Math.floor(value); }
}
