package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class HomeDestination {
    enum Decision { ACCEPT, NO_HOME, CURRENT_WORLD_UNAVAILABLE, CROSS_DIMENSION }
    enum Outcome { ACCEPT, NO_HOME, CURRENT_WORLD_UNAVAILABLE, CROSS_DIMENSION, VEHICLE_TOO_LARGE, UNSAFE }
    enum MountedChoice { EXACT, ABOVE_BED, NEEDS_FALLBACK, VEHICLE_TOO_LARGE, UNSAFE }
    record Result(Outcome outcome, DestinationSafety.Prepared destination) { }
    record RespawnAuthority(Object selectedLevel, Object dimension, BlockPos pos,
                            float yaw, float pitch, boolean forced) { }
    record SavedHome(ServerPlayer player, ServerLevel currentLevel, ServerLevel savedLevel,
                     BlockPos homeBlock, boolean forcedRespawn, RespawnAuthority authority) {
    }
    record PreparedSavedHome(SavedHome home) { }
    record ResolvedHome(PreparedSavedHome prepared, TeleportTransition respawn, Entity root) { }
    record Validation(Outcome outcome, SavedHome home) { }
    record Resolution(Outcome outcome, ResolvedHome home) { }
    record Plan(Result immediate, Pending pending) { }
    record TerrainRead(int minX, int maxX, int minZ, int maxZ) { }
    record SafetyEvaluation(Result result, TerrainRead fallbackTerrain) { }

    static final int ANCHOR_DISMOUNT_CANDIDATES = 25;
    static final int ORDINARY_BED_DISMOUNT_CANDIDATES = 12;
    static final int BUNK_BED_DISMOUNT_CANDIDATES = 22;

    static final int VANILLA_HOME_STATE_INSPECTION_WORK = 2;
    static final int VANILLA_DISMOUNT_DIRECT_BLOCK_READS = 6;
    static final int VANILLA_PLAYER_COLLISION_OWNER_CELLS = 3 * 5 * 3;
    static final int VANILLA_COLLISION_CELL_WORK = DestinationSafety.MAX_PROBE_CELL_WORK;
    static final int MAX_VANILLA_DISMOUNT_PASSES = 2 * ANCHOR_DISMOUNT_CANDIDATES;
    static final int MAX_VANILLA_RESOLUTION_WORK = VANILLA_HOME_STATE_INSPECTION_WORK
            + MAX_VANILLA_DISMOUNT_PASSES
            * (VANILLA_DISMOUNT_DIRECT_BLOCK_READS
            + VANILLA_PLAYER_COLLISION_OWNER_CELLS * VANILLA_COLLISION_CELL_WORK);
    static final int VANILLA_RESPAWN_RESOLUTION_WORK = MAX_VANILLA_RESOLUTION_WORK
            - VANILLA_HOME_STATE_INSPECTION_WORK;
    static final int INITIAL_HOME_SAFETY_WORK = DestinationSafety.MAX_SINGLE_MOUNTED_HOME_SAFETY_WORK
            + DestinationSafety.HOME_POLICY_WORK;
    static final int FALLBACK_HOME_SAFETY_WORK = DestinationSafety.MAX_SINGLE_MOUNTED_HOME_SAFETY_WORK;
    static final int RESOLUTION_AND_SAFETY_WORK = MAX_VANILLA_RESOLUTION_WORK
            + INITIAL_HOME_SAFETY_WORK + FALLBACK_HOME_SAFETY_WORK;

    interface HomeAccess {
        Validation validate();
        RespawnAuthority currentAuthority(SavedHome home);
        PreparedSavedHome prepare(SavedHome home);
        List<TerrainRead> resolutionTerrain(SavedHome home);
        Resolution resolve(PreparedSavedHome prepared);
    }

    private HomeDestination() { }

    static Decision decide(boolean currentWorldAvailable, boolean missingRespawnBlock,
                           boolean savedLevelAvailable, boolean sameDimension,
                           boolean crossDimensionEnabled) {
        if (!currentWorldAvailable) return Decision.CURRENT_WORLD_UNAVAILABLE;
        if (missingRespawnBlock || !savedLevelAvailable) return Decision.NO_HOME;
        if (!sameDimension && !crossDimensionEnabled) return Decision.CROSS_DIMENSION;
        return Decision.ACCEPT;
    }

    static boolean mayTryAboveBed(boolean mounted, boolean bed, boolean forced, boolean covered) {
        return mounted && bed && !forced && !covered;
    }

    static boolean acceptUnmounted(boolean force, BooleanSupplier safe) {
        return force || safe.getAsBoolean();
    }

    static boolean matchesRespawnAuthority(RespawnAuthority expected, RespawnAuthority current) {
        if (expected == null) return true;
        return current != null
                && expected.selectedLevel() == current.selectedLevel()
                && expected.dimension().equals(current.dimension())
                && expected.pos().equals(current.pos())
                && Float.floatToIntBits(expected.yaw()) == Float.floatToIntBits(current.yaw())
                && Float.floatToIntBits(expected.pitch()) == Float.floatToIntBits(current.pitch())
                && expected.forced() == current.forced();
    }

    static MountedChoice chooseMounted(DestinationSafety.HomeFit exact, boolean mayTryFallback,
                                       DestinationSafety.HomeFit fallback) {
        if (exact == DestinationSafety.HomeFit.FITS) return MountedChoice.EXACT;
        if (exact == DestinationSafety.HomeFit.UNSAFE) return MountedChoice.UNSAFE;
        if (!mayTryFallback) return MountedChoice.VEHICLE_TOO_LARGE;
        if (fallback == null) return MountedChoice.NEEDS_FALLBACK;
        if (fallback == DestinationSafety.HomeFit.FITS) return MountedChoice.ABOVE_BED;
        if (fallback == DestinationSafety.HomeFit.UNSAFE) return MountedChoice.UNSAFE;
        return MountedChoice.VEHICLE_TOO_LARGE;
    }


    static Plan plan(ServerPlayer player, boolean force, boolean crossDimensionEnabled) {
        MinecraftHomeAccess access = new MinecraftHomeAccess(player, crossDimensionEnabled);
        Validation validation = access.validate();
        if (validation.outcome() != Outcome.ACCEPT) {
            return new Plan(new Result(validation.outcome(), null), null);
        }
        SavedHome home = validation.home();
        DestinationSafety.ChunkPreparation preparation =
                DestinationSafety.ChunkPreparation.expandableForLevel(home.savedLevel());
        return new Plan(null, new Pending(force, access, home, preparation));
    }


    static OmwhCommands.PendingWork<Void> candidateTerrain(
            DestinationSafety.ChunkPreparation preparation,
            Iterator<TerrainRead> candidates) {
        return new OmwhCommands.PendingWork<>() {
            private TerrainRead active;

            @Override
            public OmwhCommands.PendingStep<Void> step(int candidateBudget, int worldWorkBudget) {
                int candidatesStarted = 0;
                if (active == null) {
                    if (!candidates.hasNext()) return OmwhCommands.PendingStep.complete(null, 0, 0);
                    if (candidateBudget <= 0 || worldWorkBudget <= 0) {
                        return OmwhCommands.PendingStep.pending(0, 0);
                    }
                    active = candidates.next();
                    preparation.requireTerrainRead(active);
                    candidatesStarted = 1;
                }
                int prepared = preparation.prepare(
                        SpawnDestination.PREPARATION_CHUNKS_PER_VISIT, worldWorkBudget);
                if (!preparation.terrainReadReady(active)) {
                    return OmwhCommands.PendingStep.pending(candidatesStarted, prepared);
                }
                active = null;
                if (candidates.hasNext()) {
                    return OmwhCommands.PendingStep.pending(candidatesStarted, prepared);
                }
                return OmwhCommands.PendingStep.complete(null, candidatesStarted, prepared);
            }

            @Override public int closeWork() { return preparation.closeWork(); }
            @Override public void close() { preparation.close(); }
        };
    }

    static List<TerrainRead> vanillaResolutionTerrain(BlockPos homeBlock, BlockState homeState,
                                                       boolean bunkBed, float yaw,
                                                       EntityDimensions standingDimensions) {
        // Minecraft 26.2 coupling: this mirrors RespawnAnchorBlock.findStandUpPosition and
        // BedBlock.findStandUpPosition/Player.findRespawnPositionAndUseSpawnBlock read order.
        // Re-derive the candidates and collision-owner shell from mapped vanilla source on update;
        // loading fewer chunks changes a cold-world home from pending to a false denial.
        if (homeState.getBlock() instanceof RespawnAnchorBlock) {
            List<int[]> offsets = new ArrayList<>(ANCHOR_DISMOUNT_CANDIDATES);
            int[][] horizontal = {{0, -1}, {-1, 0}, {0, 1}, {1, 0},
                    {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
            for (int y : new int[]{0, -1, 1}) {
                for (int[] offset : horizontal) offsets.add(new int[]{offset[0], y, offset[1]});
            }
            offsets.add(new int[]{0, 1, 0});
            return terrainForDismountOffsets(homeBlock, offsets, standingDimensions);
        }
        if (!(homeState.getBlock() instanceof BedBlock)) return List.of();

        Direction facing = homeState.getValue(BedBlock.FACING);
        Direction side = facing.getClockWise();
        Direction preferred = side.isFacingAngle(yaw) ? side.getOpposite() : side;
        List<int[]> surround = bedSurroundOffsets(facing, preferred);
        List<int[]> offsets = new ArrayList<>(bunkBed
                ? BUNK_BED_DISMOUNT_CANDIDATES : ORDINARY_BED_DISMOUNT_CANDIDATES);
        offsets.addAll(surround);
        if (bunkBed) offsets.addAll(surround);
        offsets.add(new int[]{0, 0, 0});
        offsets.add(new int[]{-facing.getStepX(), 0, -facing.getStepZ()});
        return terrainForDismountOffsets(homeBlock, offsets, standingDimensions);
    }

    private static List<int[]> bedSurroundOffsets(Direction facing, Direction preferred) {
        int fx = facing.getStepX();
        int fz = facing.getStepZ();
        int px = preferred.getStepX();
        int pz = preferred.getStepZ();
        return List.of(
                new int[]{px, 0, pz}, new int[]{px - fx, 0, pz - fz},
                new int[]{px - 2 * fx, 0, pz - 2 * fz}, new int[]{-2 * fx, 0, -2 * fz},
                new int[]{-px - 2 * fx, 0, -pz - 2 * fz}, new int[]{-px - fx, 0, -pz - fz},
                new int[]{-px, 0, -pz}, new int[]{-px + fx, 0, -pz + fz},
                new int[]{fx, 0, fz}, new int[]{px + fx, 0, pz + fz});
    }

    private static List<TerrainRead> terrainForDismountOffsets(BlockPos homeBlock, List<int[]> offsets,
                                                                EntityDimensions dimensions) {
        List<TerrainRead> reads = new ArrayList<>(offsets.size());
        for (int[] offset : offsets) {
            Vec3 feet = new Vec3(homeBlock.getX() + offset[0] + 0.5,
                    homeBlock.getY() + offset[1], homeBlock.getZ() + offset[2] + 0.5);
            DestinationSafety.CellRange owners = DestinationSafety.collisionOwnerCells(
                    DestinationSafety.standingPlayerBounds(feet, dimensions));
            reads.add(new TerrainRead(owners.minX(), owners.maxX(), owners.minZ(), owners.maxZ()));
        }
        return List.copyOf(reads);
    }

    static final class Pending implements AutoCloseable {
        private final boolean force;
        private final HomeAccess access;
        private final DestinationSafety.ChunkPreparation preparation;
        private final SavedHome home;
        private OmwhCommands.PendingWork<Void> terrain;
        private boolean resolutionTerrainChosen;
        private ResolvedHome resolved;
        private boolean initialSafetyEvaluated;
        private boolean fallbackRequested;
        private Result result;


        Pending(boolean force, HomeAccess access, SavedHome home,
                DestinationSafety.ChunkPreparation preparation) {
            this.force = force;
            this.access = access;
            this.home = home;
            this.preparation = preparation;
            BlockPos block = home.homeBlock();
            this.terrain = candidateTerrain(preparation,
                    List.of(new TerrainRead(block.getX(), block.getX(), block.getZ(), block.getZ())).iterator());
        }

        OmwhCommands.PendingStep<Result> step(int candidateBudget, int worldWorkBudget) {
            if (result != null) throw new IllegalStateException("pending home is already complete");
            if (!authorityCurrent()) {
                result = new Result(Outcome.NO_HOME, null);
                return OmwhCommands.PendingStep.complete(result, 0, 0);
            }
            int candidatesUsed = 0;
            int worldWorkUsed = 0;
            if (terrain != null) {
                OmwhCommands.PendingStep<Void> prepared = terrain.step(candidateBudget, worldWorkBudget);
                candidatesUsed = prepared.candidatesUsed();
                worldWorkUsed = prepared.worldWorkUsed();
                if (!prepared.complete()) return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
                if (!resolutionTerrainChosen) {
                    if (worldWorkBudget - worldWorkUsed < VANILLA_HOME_STATE_INSPECTION_WORK) {
                        return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
                    }
                    worldWorkUsed += VANILLA_HOME_STATE_INSPECTION_WORK;
                    resolutionTerrainChosen = true;
                    terrain = candidateTerrain(preparation, access.resolutionTerrain(home).iterator());
                    return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
                }
                terrain = null;
            }

            if (resolved == null) {
                if (worldWorkBudget - worldWorkUsed < VANILLA_RESPAWN_RESOLUTION_WORK) {
                    return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
                }
                worldWorkUsed += VANILLA_RESPAWN_RESOLUTION_WORK;
                Resolution resolution = access.resolve(access.prepare(home));
                if (resolution.outcome() != Outcome.ACCEPT) {
                    result = new Result(resolution.outcome(), null);
                    return OmwhCommands.PendingStep.complete(result, candidatesUsed, worldWorkUsed);
                }
                resolved = resolution.home();
                TerrainRead initial = initialSafetyTerrain(resolved, force);
                if (initial != null) {
                    terrain = candidateTerrain(preparation, List.of(initial).iterator());
                    return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
                }
            }

            if (!initialSafetyEvaluated) {
                if (worldWorkBudget - worldWorkUsed < INITIAL_HOME_SAFETY_WORK) {
                    return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
                }
                worldWorkUsed += INITIAL_HOME_SAFETY_WORK;
                initialSafetyEvaluated = true;
                SafetyEvaluation evaluated = evaluateInitial(resolved, force);
                if (evaluated.result() != null) {
                    result = evaluated.result();
                    return OmwhCommands.PendingStep.complete(result, candidatesUsed, worldWorkUsed);
                }
                if (evaluated.fallbackTerrain() == null) {
                    throw new IllegalStateException("home safety requested no result and no fallback terrain");
                }
                fallbackRequested = true;
                terrain = candidateTerrain(preparation, List.of(evaluated.fallbackTerrain()).iterator());
                return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
            }

            if (!fallbackRequested) throw new IllegalStateException("home safety completed without a result");
            if (worldWorkBudget - worldWorkUsed < FALLBACK_HOME_SAFETY_WORK) {
                return OmwhCommands.PendingStep.pending(candidatesUsed, worldWorkUsed);
            }
            worldWorkUsed += FALLBACK_HOME_SAFETY_WORK;
            result = evaluateFallback(resolved);
            return OmwhCommands.PendingStep.complete(result, candidatesUsed, worldWorkUsed);
        }

        int closeWork() { return preparation.closeWork(); }
        boolean authorityCurrent() {
            return matchesRespawnAuthority(home.authority(), access.currentAuthority(home));
        }
        @Override public void close() { preparation.close(); }
    }


    static TerrainRead initialSafetyTerrain(ResolvedHome resolved, boolean force) {
        if (force) return null;
        SavedHome home = resolved.prepared().home();
        Entity root = resolved.root();
        if (root == home.player()) {
            DestinationSafety.Bounds occupied = DestinationSafety.standingPlayerBounds(
                    resolved.respawn().position(), home.player().getDimensions(Pose.STANDING));
            return terrain(DestinationSafety.homeHazardCells(occupied));
        }
        DestinationSafety.Bounds rootBounds = DestinationSafety.boundsAt(root, resolved.respawn().position());
        return terrain(DestinationSafety.collisionOwnerCells(
                DestinationSafety.mountedHomeClearance(rootBounds)));
    }

    static SafetyEvaluation evaluateInitial(ResolvedHome resolved, boolean force) {
        SavedHome home = resolved.prepared().home();
        ServerPlayer player = home.player();
        TeleportTransition respawn = resolved.respawn();
        Entity root = resolved.root();
        if (root == player) {
            if (!acceptUnmounted(force, () -> DestinationSafety.unmountedHomeFits(
                    player, respawn.newLevel(), respawn.position()))) {
                return new SafetyEvaluation(new Result(Outcome.UNSAFE, null), null);
            }
            return new SafetyEvaluation(new Result(Outcome.ACCEPT, DestinationSafety.Prepared.ordinary(
                    respawn.newLevel(), respawn.position(), respawn.yRot(), respawn.xRot())), null);
        }
        if (force) {
            return new SafetyEvaluation(new Result(Outcome.ACCEPT, DestinationSafety.Prepared.ordinary(
                    respawn.newLevel(), respawn.position(), root.getYRot(), root.getXRot())), null);
        }

        DestinationSafety.RootGeometry geometry = DestinationSafety.rootGeometry(root);
        if (!DestinationSafety.rootGeometrySupported(geometry.width(), geometry.clearHeight())) {
            return new SafetyEvaluation(new Result(Outcome.VEHICLE_TOO_LARGE, null), null);
        }
        BlockPos homeBlock = home.homeBlock();
        DestinationSafety.HomeFit exactFit = DestinationSafety.mountedHomeFit(
                root, respawn.newLevel(), respawn.position(), homeBlock);
        boolean bed = respawn.newLevel().getBlockState(homeBlock).getBlock() instanceof BedBlock;
        boolean covered = bed && isCoveredBed(respawn.newLevel(), homeBlock);
        MountedChoice choice = chooseMounted(exactFit,
                mayTryAboveBed(true, bed, home.forcedRespawn(), covered), null);
        if (choice == MountedChoice.EXACT) {
            return new SafetyEvaluation(acceptedMounted(resolved, respawn.position()), null);
        }
        if (choice == MountedChoice.UNSAFE) {
            return new SafetyEvaluation(new Result(Outcome.UNSAFE, null), null);
        }
        if (choice == MountedChoice.VEHICLE_TOO_LARGE) {
            return new SafetyEvaluation(new Result(Outcome.VEHICLE_TOO_LARGE, null), null);
        }
        Vec3 aboveBed = aboveBed(homeBlock);
        DestinationSafety.Bounds fallbackBounds = DestinationSafety.boundsAt(root, aboveBed);
        return new SafetyEvaluation(null, terrain(DestinationSafety.collisionOwnerCells(
                DestinationSafety.mountedHomeClearance(fallbackBounds))));
    }

    static Result evaluateFallback(ResolvedHome resolved) {
        BlockPos homeBlock = resolved.prepared().home().homeBlock();
        Vec3 aboveBed = aboveBed(homeBlock);
        DestinationSafety.HomeFit fallback = DestinationSafety.mountedHomeFit(
                resolved.root(), resolved.respawn().newLevel(), aboveBed, homeBlock);
        return switch (chooseMounted(DestinationSafety.HomeFit.BLOCKED, true, fallback)) {
            case ABOVE_BED -> acceptedMounted(resolved, aboveBed);
            case UNSAFE -> new Result(Outcome.UNSAFE, null);
            case VEHICLE_TOO_LARGE -> new Result(Outcome.VEHICLE_TOO_LARGE, null);
            case EXACT, NEEDS_FALLBACK -> throw new IllegalStateException("invalid mounted fallback decision");
        };
    }

    static Result evaluate(ResolvedHome resolved, boolean force) {
        SafetyEvaluation initial = evaluateInitial(resolved, force);
        return initial.result() != null ? initial.result() : evaluateFallback(resolved);
    }

    private static Result acceptedMounted(ResolvedHome resolved, Vec3 position) {
        Entity root = resolved.root();
        return new Result(Outcome.ACCEPT, DestinationSafety.Prepared.ordinary(
                resolved.respawn().newLevel(), position, root.getYRot(), root.getXRot()));
    }

    private static Vec3 aboveBed(BlockPos homeBlock) {
        return new Vec3(homeBlock.getX() + 0.5, homeBlock.getY() + 1.0, homeBlock.getZ() + 0.5);
    }

    private static TerrainRead terrain(DestinationSafety.CellRange cells) {
        return new TerrainRead(cells.minX(), cells.maxX(), cells.minZ(), cells.maxZ());
    }

    private static final class MinecraftHomeAccess implements HomeAccess {
        private final ServerPlayer player;
        private final boolean crossDimensionEnabled;

        private MinecraftHomeAccess(ServerPlayer player, boolean crossDimensionEnabled) {
            this.player = player;
            this.crossDimensionEnabled = crossDimensionEnabled;
        }

        @Override
        public Validation validate() {
            ServerLevel current = player.level() instanceof ServerLevel level ? level : null;
            var respawnConfig = player.getRespawnConfig();
            Decision initial = decide(current != null, respawnConfig == null, true, true,
                    crossDimensionEnabled);
            if (initial != Decision.ACCEPT) return new Validation(outcome(initial), null);

            ServerLevel savedLevel = current.getServer().getLevel(respawnConfig.respawnData().dimension());
            Decision decision = decide(true, false, savedLevel != null,
                    savedLevel != null && current.dimension().equals(savedLevel.dimension()),
                    crossDimensionEnabled);
            if (decision != Decision.ACCEPT) return new Validation(outcome(decision), null);
            RespawnAuthority authority = authority(respawnConfig, savedLevel);
            return new Validation(Outcome.ACCEPT, new SavedHome(player, current, savedLevel,
                    respawnConfig.respawnData().pos(), respawnConfig.forced(), authority));
        }

        @Override
        public RespawnAuthority currentAuthority(SavedHome home) {
            var current = player.getRespawnConfig();
            if (current == null || !(player.level() instanceof ServerLevel level)) return null;
            ServerLevel selected = level.getServer().getLevel(current.respawnData().dimension());
            if (selected == null) return null;
            return authority(current, selected);
        }

        @Override
        public PreparedSavedHome prepare(SavedHome home) {
            return new PreparedSavedHome(home);
        }

        @Override
        public List<TerrainRead> resolutionTerrain(SavedHome home) {
            BlockState state = home.savedLevel().getBlockState(home.homeBlock());
            boolean bunkBed = state.getBlock() instanceof BedBlock
                    && home.savedLevel().getBlockState(home.homeBlock().below()).getBlock() instanceof BedBlock;
            float yaw = home.authority().yaw();
            return vanillaResolutionTerrain(home.homeBlock(), state, bunkBed, yaw,
                    home.player().getDimensions(Pose.STANDING));
        }

        private static RespawnAuthority authority(ServerPlayer.RespawnConfig config, ServerLevel selectedLevel) {
            var data = config.respawnData();
            return new RespawnAuthority(selectedLevel, data.dimension(), data.pos(),
                    data.yaw(), data.pitch(), config.forced());
        }

        @Override
        public Resolution resolve(PreparedSavedHome prepared) {
            // false preserves respawn-anchor charges; vanilla remains the authority for placement.
            TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(
                    false, TeleportTransition.DO_NOTHING);
            Decision decision = decide(true, respawn.missingRespawnBlock(), true, true,
                    crossDimensionEnabled);
            if (decision != Decision.ACCEPT) return new Resolution(outcome(decision), null);
            return new Resolution(Outcome.ACCEPT,
                    new ResolvedHome(prepared, respawn, player.getRootVehicle()));
        }


    }

    private static Outcome outcome(Decision decision) {
        return switch (decision) {
            case ACCEPT -> Outcome.ACCEPT;
            case NO_HOME -> Outcome.NO_HOME;
            case CURRENT_WORLD_UNAVAILABLE -> Outcome.CURRENT_WORLD_UNAVAILABLE;
            case CROSS_DIMENSION -> Outcome.CROSS_DIMENSION;
        };
    }

    private static boolean isCoveredBed(ServerLevel level, BlockPos homeBlock) {
        var homeState = level.getBlockState(homeBlock);
        BlockPos other = homeBlock.relative(BedBlock.getConnectedDirection(homeState));
        return !level.getBlockState(homeBlock.above()).getCollisionShape(level, homeBlock.above()).isEmpty()
                || !level.getBlockState(other.above()).getCollisionShape(level, other.above()).isEmpty();
    }
}
