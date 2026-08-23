package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

public final class DestinationSafety {
    static final double HOME_HORIZONTAL_MARGIN = 0.5;
    static final double HOME_UPPER_MARGIN = 1.5;
    static final int MAX_SUPPORTED_ROOT_WIDTH = 14;
    static final int MAX_SUPPORTED_CLEAR_HEIGHT = 16;
    static final int DESTINATION_CHUNK_CAP = 5 * 5;
    private static final int BLOCK_READ_WORK = 1;
    private static final int COLLISION_INTERSECTION_WORK = 8;
    private static final int MAX_ROOT_BLOCK_WIDTH = MAX_SUPPORTED_ROOT_WIDTH + 1;
    private static final int MAX_ROOT_BLOCK_HEIGHT = MAX_SUPPORTED_CLEAR_HEIGHT - 1;
    private static final int MAX_HOME_CLEARANCE_WIDTH = MAX_ROOT_BLOCK_WIDTH + 1;
    private static final int MAX_HOME_CLEARANCE_HEIGHT = MAX_ROOT_BLOCK_HEIGHT + 2;
    private static final int MAX_HOME_COLLISION_OWNER_WIDTH = MAX_HOME_CLEARANCE_WIDTH + 2;
    private static final int MAX_HOME_COLLISION_OWNER_HEIGHT = MAX_HOME_CLEARANCE_HEIGHT + 2;
    private static final int MAX_HOME_HAZARD_CELLS = MAX_ROOT_BLOCK_WIDTH
            * (MAX_ROOT_BLOCK_HEIGHT + 1) * MAX_ROOT_BLOCK_WIDTH;
    private static final int MAX_HOME_COLLISION_CELLS = MAX_HOME_COLLISION_OWNER_WIDTH
            * MAX_HOME_COLLISION_OWNER_HEIGHT * MAX_HOME_COLLISION_OWNER_WIDTH;
    private static final int MAX_HOME_CHUNK_PROBES = 3 * 3;
    private static final int CONFIGURED_BED_READS = 2;
    static final int MAX_SINGLE_MOUNTED_HOME_SAFETY_WORK = MAX_HOME_CHUNK_PROBES
            + CONFIGURED_BED_READS * BLOCK_READ_WORK
            + MAX_HOME_HAZARD_CELLS * BLOCK_READ_WORK
            + MAX_HOME_COLLISION_CELLS * (BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK);
    static final int HOME_POLICY_WORK = 4 * BLOCK_READ_WORK + 2 * COLLISION_INTERSECTION_WORK;
    static final int MAX_MOUNTED_HOME_SAFETY_WORK = 2 * MAX_SINGLE_MOUNTED_HOME_SAFETY_WORK
            + HOME_POLICY_WORK;
    private static final int MAX_END_COLLISION_OWNER_WIDTH = MAX_ROOT_BLOCK_WIDTH + 2;
    private static final int MAX_END_COLLISION_OWNER_HEIGHT = MAX_ROOT_BLOCK_HEIGHT + 2;
    private static final int MAX_END_OCCUPIED_CELLS = MAX_ROOT_BLOCK_WIDTH
            * MAX_ROOT_BLOCK_HEIGHT * MAX_ROOT_BLOCK_WIDTH;
    private static final int MAX_END_COLLISION_CELLS = MAX_END_COLLISION_OWNER_WIDTH
            * MAX_END_COLLISION_OWNER_HEIGHT * MAX_END_COLLISION_OWNER_WIDTH;
    private static final int MAX_END_SUPPORT_CELLS = MAX_ROOT_BLOCK_WIDTH * MAX_ROOT_BLOCK_WIDTH;
    private static final int MAX_END_CHUNK_PROBES = 3 * 3;
    static final int MAX_SINGLE_END_SAFETY_WORK = MAX_END_CHUNK_PROBES
            + MAX_END_COLLISION_CELLS * (BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK)
            + MAX_END_OCCUPIED_CELLS * BLOCK_READ_WORK
            + MAX_END_SUPPORT_CELLS * (BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK);
    static final int MAX_PLAYER_END_SAFETY_WORK = 4 + 80 * (BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK)
            + 12 * BLOCK_READ_WORK + 4 * (BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK);
    static final int MAX_END_SAFETY_WORK = MAX_SINGLE_END_SAFETY_WORK + MAX_PLAYER_END_SAFETY_WORK;
    private static final int MAX_SPAWN_SUPPORT_CELLS = MAX_SUPPORTED_ROOT_WIDTH * MAX_SUPPORTED_ROOT_WIDTH;
    private static final int MAX_SPAWN_OCCUPIED_CELLS = MAX_SUPPORTED_ROOT_WIDTH
            * MAX_SUPPORTED_CLEAR_HEIGHT * MAX_SUPPORTED_ROOT_WIDTH;
    private static final int MAX_SPAWN_COLLISION_CELLS = (MAX_SUPPORTED_ROOT_WIDTH + 2)
            * (MAX_SUPPORTED_CLEAR_HEIGHT + 2) * (MAX_SUPPORTED_ROOT_WIDTH + 2);
    static final int MAX_SPAWN_CANDIDATE_WORK = MAX_SPAWN_SUPPORT_CELLS
            * (BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK)
            + MAX_SPAWN_OCCUPIED_CELLS * BLOCK_READ_WORK
            + MAX_SPAWN_COLLISION_CELLS * (BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK);

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }
    record Footprint(int minX, int maxX, int minZ, int maxZ) { }
    record CellRange(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) { }
    record Cell(int x, int y, int z) { }
    record RootGeometry(int width, int clearHeight) { }
    static final class ChunkResidency {
        private static final Object RESIDENT = new Object();
        private final int minChunkX;
        private final int minChunkZ;
        private final int width;
        private final int depth;
        private final Object[] chunks;
        private final int chunkProbes;
        private final int residentCount;

        private ChunkResidency(int minChunkX, int minChunkZ, int width, int depth,
                               Object[] chunks, int chunkProbes, int residentCount) {
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.width = width;
            this.depth = depth;
            this.chunks = chunks;
            this.chunkProbes = chunkProbes;
            this.residentCount = residentCount;
        }

        static ChunkResidency capture(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ,
                                      LongPredicate loaded) {
            return captureValues(minBlockX, maxBlockX, minBlockZ, maxBlockZ,
                    chunk -> loaded.test(chunk) ? RESIDENT : null);
        }

        static ChunkResidency captureValues(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ,
                                            LongFunction<?> loader) {
            int minChunkX = chunkCoordinate(minBlockX);
            int maxChunkX = chunkCoordinate(maxBlockX);
            int minChunkZ = chunkCoordinate(minBlockZ);
            int maxChunkZ = chunkCoordinate(maxBlockZ);
            int width = maxChunkX - minChunkX + 1;
            int depth = maxChunkZ - minChunkZ + 1;
            Object[] chunks = new Object[Math.multiplyExact(width, depth)];
            int probes = 0;
            int residentCount = 0;
            for (int x = minChunkX; x <= maxChunkX; x++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    Object chunk = loader.apply(chunkKey(x, z));
                    chunks[(x - minChunkX) * depth + z - minChunkZ] = chunk;
                    probes++;
                    if (chunk != null) residentCount++;
                }
            }
            return new ChunkResidency(minChunkX, minChunkZ, width, depth,
                    chunks, probes, residentCount);
        }

        boolean fullyCold() { return residentCount == 0; }
        int chunkProbes() { return chunkProbes; }

        boolean coversBlockRange(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ) {
            int firstX = chunkCoordinate(minBlockX);
            int lastX = chunkCoordinate(maxBlockX);
            int firstZ = chunkCoordinate(minBlockZ);
            int lastZ = chunkCoordinate(maxBlockZ);
            if (firstX < minChunkX || lastX >= minChunkX + width
                    || firstZ < minChunkZ || lastZ >= minChunkZ + depth) return false;
            for (int x = firstX; x <= lastX; x++) {
                for (int z = firstZ; z <= lastZ; z++) {
                    if (chunks[(x - minChunkX) * depth + z - minChunkZ] == null) return false;
                }
            }
            return true;
        }

        Object chunkAtBlock(int blockX, int blockZ) {
            int chunkX = chunkCoordinate(blockX);
            int chunkZ = chunkCoordinate(blockZ);
            if (chunkX < minChunkX || chunkX >= minChunkX + width
                    || chunkZ < minChunkZ || chunkZ >= minChunkZ + depth) return null;
            return chunks[(chunkX - minChunkX) * depth + chunkZ - minChunkZ];
        }
    }
    record Prepared(TeleportTransition transition, boolean clearVelocity) {
        Prepared(TeleportTransition transition) {
            this(transition, false);
        }

        static Prepared ordinary(ServerLevel level, Vec3 position, float yaw, float pitch) {
            return new Prepared(new TeleportTransition(
                    level, position, Vec3.ZERO, yaw, pitch, TeleportTransition.DO_NOTHING), true);
        }

        ServerLevel level() { return transition.newLevel(); }
        Vec3 position() { return transition.position(); }
    }
    enum HomeFit { FITS, BLOCKED, UNSAFE }

    private interface ProbeAccess {
        int minY();
        int maxY();
        boolean withinBorder(double minX, double maxX, double minZ, double maxZ);
        BlockState blockState(Object capturedChunk, BlockPos position);
        boolean fullSupport(BlockState state, BlockPos position);
        boolean collides(BlockState state, BlockPos position,
                         double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ);
    }

    static final class SpawnProbe implements SpawnDestination.CandidateProbe {
        static final int BLOCK_READ_WORK = 1;
        static final int COLLISION_INTERSECTION_WORK = 8;
        static final int MAX_CELL_WORK = BLOCK_READ_WORK + COLLISION_INTERSECTION_WORK;
        private static final int REJECTED = -1;
        private static final int SUPPORT = 0;
        private static final int OCCUPIED = 1;
        private static final int COLLISION = 2;
        private static final int COMPLETE = 3;

        private final ProbeAccess access;
        private final BlockPos center;
        private final int rootWidth;
        private final int rootHeight;
        private final boolean rootGeometrySupported;
        private final ChunkResidency residency;
        private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        private int phase;
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        private int minY;
        private int maxY;
        private int x;
        private int y;
        private int z;

        SpawnProbe(ServerLevel level, BlockPos center, int rootWidth, int rootHeight,
                   ChunkResidency residency) {
            this(level, center, rootWidth, rootHeight, true, residency);
        }

        SpawnProbe(ServerLevel level, BlockPos center, int rootWidth, int rootHeight,
                   boolean rootGeometrySupported, ChunkResidency residency) {
            this(new ProbeAccess() {
                @Override public int minY() { return level.getMinY(); }
                @Override public int maxY() { return level.getMaxY(); }
                @Override public boolean withinBorder(double minX, double maxX, double minZ, double maxZ) {
                    return minX >= level.getWorldBorder().getMinX() && maxX <= level.getWorldBorder().getMaxX()
                            && minZ >= level.getWorldBorder().getMinZ() && maxZ <= level.getWorldBorder().getMaxZ();
                }
                @Override public BlockState blockState(Object capturedChunk, BlockPos position) {
                    if (!(capturedChunk instanceof LevelChunk chunk)) return null;
                    return chunk.getBlockState(position);
                }
                @Override public boolean fullSupport(BlockState state, BlockPos position) {
                    return state.isCollisionShapeFullBlock(level, position);
                }
                @Override public boolean collides(BlockState state, BlockPos position,
                                                  double minX, double minY, double minZ,
                                                  double maxX, double maxY, double maxZ) {
                    return Shapes.joinIsNotEmpty(
                            state.getCollisionShape(level, position, CollisionContext.empty()),
                            Shapes.box(minX - position.getX(), minY - position.getY(), minZ - position.getZ(),
                                    maxX - position.getX(), maxY - position.getY(), maxZ - position.getZ()),
                            BooleanOp.AND);
                }
            }, center, rootWidth, rootHeight, rootGeometrySupported, residency);
        }

        private SpawnProbe(ProbeAccess access, BlockPos center, int rootWidth, int rootHeight,
                           boolean rootGeometrySupported, ChunkResidency residency) {
            this.access = access;
            this.center = center;
            this.rootWidth = rootWidth;
            this.rootHeight = rootHeight;
            this.rootGeometrySupported = rootGeometrySupported;
            this.residency = residency;
        }

        static SpawnProbe controlled(BlockPos center, int rootWidth, int rootHeight,
                                     ChunkResidency residency, Function<BlockPos, BlockState> states) {
            return new SpawnProbe(new ProbeAccess() {
                @Override public int minY() { return -1_000_000; }
                @Override public int maxY() { return 1_000_000; }
                @Override public boolean withinBorder(double minX, double maxX, double minZ, double maxZ) {
                    return true;
                }
                @Override public BlockState blockState(Object capturedChunk, BlockPos position) {
                    return states.apply(position);
                }
                @Override public boolean fullSupport(BlockState state, BlockPos position) {
                    return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, position);
                }
                @Override public boolean collides(BlockState state, BlockPos position,
                                                  double minX, double minY, double minZ,
                                                  double maxX, double maxY, double maxZ) {
                    return Shapes.joinIsNotEmpty(
                            state.getCollisionShape(EmptyBlockGetter.INSTANCE, position, CollisionContext.empty()),
                            Shapes.box(minX - position.getX(), minY - position.getY(), minZ - position.getZ(),
                                    maxX - position.getX(), maxY - position.getY(), maxZ - position.getZ()),
                            BooleanOp.AND);
                }
            }, center, rootWidth, rootHeight, true, residency);
        }

        @Override
        public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind kind) {
            if (kind == SpawnDestination.ProbeKind.ROOT && !rootGeometrySupported) {
                phase = REJECTED;
                return;
            }
            int width = kind == SpawnDestination.ProbeKind.ROOT ? rootWidth : 1;
            int height = kind == SpawnDestination.ProbeKind.ROOT ? rootHeight : 2;
            int feetX = center.getX() + offset.x();
            int feetY = center.getY() + offset.y();
            int feetZ = center.getZ() + offset.z();
            double centerOffset = width % 2 == 0 ? 0.0 : 0.5;
            double half = width / 2.0;
            minX = floor(feetX + centerOffset - half);
            maxX = floor(Math.nextDown(feetX + centerOffset + half));
            minZ = floor(feetZ + centerOffset - half);
            maxZ = floor(Math.nextDown(feetZ + centerOffset + half));
            minY = feetY;
            maxY = feetY + height - 1;

            double occupiedMaxX = maxX + 1.0;
            double occupiedMaxZ = maxZ + 1.0;
            boolean inBorder = access.withinBorder(minX, occupiedMaxX, minZ, occupiedMaxZ);
            if (minY < access.minY() || maxY + 1.0 > access.maxY() + 1.0 || !inBorder
                    || !residency.coversBlockRange(minX - 1, maxX + 1, minZ - 1, maxZ + 1)) {
                phase = REJECTED;
                return;
            }
            phase = SUPPORT;
            x = minX;
            z = minZ;
            y = minY;
        }

        @Override
        public SpawnDestination.ProbeStep step(int availableWorldWork) {
            int used = 0;
            while (used < availableWorldWork) {
                if (phase == REJECTED) {
                    return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.REJECTED, used);
                }
                if (phase == COMPLETE) {
                    return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.FITS, used);
                }
                int requiredWork = BLOCK_READ_WORK
                        + (phase == SUPPORT || phase == COLLISION ? COLLISION_INTERSECTION_WORK : 0);
                if (availableWorldWork - used < requiredWork) break;
                position.set(x, phase == SUPPORT ? minY - 1 : y, z);
                Object captured = residency.chunkAtBlock(x, z);
                if (captured == null) {
                    phase = REJECTED;
                    continue;
                }
                BlockState state = access.blockState(captured, position);
                if (state == null) {
                    phase = REJECTED;
                    continue;
                }
                used += requiredWork;
                if (phase == SUPPORT) {
                    if (!state.getFluidState().isEmpty()
                            || !access.fullSupport(state, position)
                            || isHazard(state.getBlock())) phase = REJECTED;
                    else advanceSupport();
                } else if (phase == OCCUPIED) {
                    if (!state.getFluidState().isEmpty() || isHazard(state.getBlock())) phase = REJECTED;
                    else advanceOccupied();
                } else {
                    if (access.collides(state, position, minX, minY, minZ,
                            maxX + 1.0, maxY + 1.0, maxZ + 1.0)) phase = REJECTED;
                    else advanceCollision();
                }
            }
            if (phase == REJECTED) return new SpawnDestination.ProbeStep(
                    SpawnDestination.ProbeOutcome.REJECTED, used);
            if (phase == COMPLETE) return new SpawnDestination.ProbeStep(
                    SpawnDestination.ProbeOutcome.FITS, used);
            return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.INCOMPLETE, used);
        }

        private void advanceSupport() {
            if (++z <= maxZ) return;
            z = minZ;
            if (++x <= maxX) return;
            phase = OCCUPIED;
            x = minX;
            y = minY;
        }

        private void advanceOccupied() {
            if (++y <= maxY) return;
            y = minY;
            if (++z <= maxZ) return;
            z = minZ;
            if (++x <= maxX) return;
            phase = COLLISION;
            x = minX - 1;
            y = minY - 1;
            z = minZ - 1;
        }

        private void advanceCollision() {
            if (++z <= maxZ + 1) return;
            z = minZ - 1;
            if (++y <= maxY + 1) return;
            y = minY - 1;
            if (++x <= maxX + 1) return;
            phase = COMPLETE;
        }
    }

    private DestinationSafety() { }

    static Bounds mountedHomeClearance(Bounds root) {
        return new Bounds(root.minX - HOME_HORIZONTAL_MARGIN, root.minY,
                root.minZ - HOME_HORIZONTAL_MARGIN, root.maxX + HOME_HORIZONTAL_MARGIN,
                root.maxY + HOME_UPPER_MARGIN, root.maxZ + HOME_HORIZONTAL_MARGIN);
    }

    static RootGeometry rootGeometry(Entity root) {
        return new RootGeometry((int) Math.max(1, Math.ceil(root.getBbWidth())),
                (int) Math.max(3, Math.ceil(root.getBbHeight()) + 2));
    }

    static boolean rootGeometrySupported(int width, int clearHeight) {
        return width <= MAX_SUPPORTED_ROOT_WIDTH && clearHeight <= MAX_SUPPORTED_CLEAR_HEIGHT;
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

    static boolean allChunksLoaded(CellRange cells, LongPredicate loaded) {
        for (long chunk : involvedChunks(cells)) {
            if (!loaded.test(chunk)) return false;
        }
        return true;
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

    static boolean loadedAndCollisionFree(Bounds occupied, LongPredicate loaded,
                                          Function<Cell, List<Bounds>> collisionShapes) {
        CellRange owners = collisionOwnerCells(occupied);
        return allChunksLoaded(owners, loaded) && collisionFree(occupied, owners, collisionShapes);
    }

    static boolean isHazard(Block block) {
        return block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.LAVA
                || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.WITHER_ROSE
                || block == Blocks.POWDER_SNOW;
    }

    static boolean isUnsafeCell(boolean hasFluid, Block block) {
        return hasFluid || isHazard(block);
    }

    static CellRange homeHazardCells(Bounds occupied) {
        return new CellRange(floor(occupied.minX), floor(Math.nextDown(occupied.maxX)),
                floor(occupied.minY) - 1, floor(Math.nextDown(occupied.maxY)),
                floor(occupied.minZ), floor(Math.nextDown(occupied.maxZ)));
    }

    static boolean unmountedHomeFits(Entity player, ServerLevel level, Vec3 position) {
        Bounds occupied = standingPlayerBounds(position, player.getDimensions(Pose.STANDING));
        AABB occupiedBox = box(occupied);
        if (!withinBuildHeight(occupied, level.getMinY(), level.getMaxY())
                || !level.getWorldBorder().isWithinBounds(occupiedBox)) return false;

        CellRange checked = homeHazardCells(occupied);
        preloadInvolvedChunks(checked, new HashSet<>(), chunk -> loadChunk(level, chunk));
        return !containsHomeHazard(level, checked);
    }

    static Bounds standingPlayerBounds(Vec3 position, EntityDimensions standingDimensions) {
        return bounds(standingDimensions.makeBoundingBox(position));
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
        ConfiguredBed configuredBed = configuredBed(level, homeBlock);
        CollisionContext context = CollisionContext.of(root);
        for (int x = owners.minX; x <= owners.maxX; x++) {
            for (int y = owners.minY; y <= owners.maxY; y++) {
                for (int z = owners.minZ; z <= owners.maxZ; z++) {
                    BlockPos blockPos = new BlockPos(x, y, z);
                    var state = level.getBlockState(blockPos);
                    boolean bedPart = configuredBed.contains(blockPos);
                    AABB checked = bedPart ? rootBox : clearanceBox;
                    if (Shapes.joinIsNotEmpty(
                            state.getCollisionShape(level, blockPos, context),
                            Shapes.box(checked.minX - x, checked.minY - y, checked.minZ - z,
                                    checked.maxX - x, checked.maxY - y, checked.maxZ - z),
                            BooleanOp.AND)) return HomeFit.BLOCKED;
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
                    if (isUnsafeCell(!state.getFluidState().isEmpty(), state.getBlock())) return true;
                }
            }
        }
        return false;
    }

    static boolean endFits(ServerLevel level, Entity entity, Vec3 position) {
        AABB occupiedBox = entity.getBoundingBox().move(position.subtract(entity.position()));
        Bounds occupied = bounds(occupiedBox);
        if (!withinBuildHeight(occupied, level.getMinY(), level.getMaxY())
                || !level.getWorldBorder().isWithinBounds(occupiedBox)) return false;

        CollisionContext context = CollisionContext.of(entity);
        CellRange owners = collisionOwnerCells(occupied);
        if (!allChunksLoaded(owners, chunk -> level.getChunkSource().getChunkNow(
                (int) (chunk >> 32), (int) chunk) != null)) return false;
        for (int x = owners.minX; x <= owners.maxX; x++) {
            for (int y = owners.minY; y <= owners.maxY; y++) {
                for (int z = owners.minZ; z <= owners.maxZ; z++) {
                    BlockPos blockPos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(blockPos);
                    if (Shapes.joinIsNotEmpty(
                            state.getCollisionShape(level, blockPos, context),
                            Shapes.box(occupied.minX - x, occupied.minY - y, occupied.minZ - z,
                                    occupied.maxX - x, occupied.maxY - y, occupied.maxZ - z),
                            BooleanOp.AND)) return false;
                }
            }
        }
        for (int x = floor(occupied.minX); x <= floor(Math.nextDown(occupied.maxX)); x++) {
            for (int y = floor(occupied.minY); y <= floor(Math.nextDown(occupied.maxY)); y++) {
                for (int z = floor(occupied.minZ); z <= floor(Math.nextDown(occupied.maxZ)); z++) {
                    var state = level.getBlockState(new BlockPos(x, y, z));
                    if (isUnsafeCell(!state.getFluidState().isEmpty(), state.getBlock())) return false;
                }
            }
        }
        int supportY = floor(occupied.minY) - 1;
        for (int x = floor(occupied.minX); x <= floor(Math.nextDown(occupied.maxX)); x++) {
            for (int z = floor(occupied.minZ); z <= floor(Math.nextDown(occupied.maxZ)); z++) {
                BlockPos supportPos = new BlockPos(x, supportY, z);
                var support = level.getBlockState(supportPos);
                if (!support.getFluidState().isEmpty()
                        || !support.isCollisionShapeFullBlock(level, supportPos)
                        || isHazard(support.getBlock())) return false;
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
        List<Long> chunks = new ArrayList<>(DESTINATION_CHUNK_CAP);
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


    private static List<Bounds> collisionShapes(ServerLevel level, Cell cell, CollisionContext context) {
        BlockPos position = new BlockPos(cell.x, cell.y, cell.z);
        List<Bounds> shapes = new ArrayList<>();
        for (AABB local : level.getBlockState(position).getCollisionShape(level, position, context).toAabbs()) {
            shapes.add(bounds(local.move(position)));
        }
        return List.copyOf(shapes);
    }


    private record ConfiguredBed(BlockPos primary, BlockPos secondary) {
        boolean contains(BlockPos candidate) {
            return candidate.equals(primary) || secondary != null && candidate.equals(secondary);
        }
    }

    private static ConfiguredBed configuredBed(ServerLevel level, BlockPos homeBlock) {
        var homeState = level.getBlockState(homeBlock);
        if (!(homeState.getBlock() instanceof BedBlock)) return new ConfiguredBed(null, null);
        BlockPos other = homeBlock.relative(BedBlock.getConnectedDirection(homeState));
        return new ConfiguredBed(homeBlock,
                level.getBlockState(other).getBlock() instanceof BedBlock ? other : null);
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
