package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

public final class HomeDestination {
    enum Decision { ACCEPT, NO_HOME, CROSS_DIMENSION }
    enum Outcome { ACCEPT, NO_HOME, CROSS_DIMENSION, VEHICLE_TOO_LARGE, UNSAFE }
    enum MountedChoice { EXACT, ABOVE_BED, VEHICLE_TOO_LARGE, UNSAFE }
    record Result(Outcome outcome, DestinationSafety.Prepared destination) { }
    record SavedHome(ServerPlayer player, ServerLevel currentLevel, ServerLevel savedLevel,
                     BlockPos homeBlock, boolean forcedRespawn) { }
    record PreparedSavedHome(SavedHome home) { }
    record ResolvedHome(PreparedSavedHome prepared, TeleportTransition respawn, Entity root) { }
    record Validation(Outcome outcome, SavedHome home) { }
    record Resolution(Outcome outcome, ResolvedHome home) { }

    interface HomeAccess {
        Validation validate();
        PreparedSavedHome prepare(SavedHome home);
        Resolution resolve(PreparedSavedHome prepared);
        Result evaluate(ResolvedHome resolved, boolean force);
    }

    private HomeDestination() { }

    static Decision decide(boolean missingRespawnBlock, boolean savedLevelAvailable, boolean sameDimension,
                           boolean crossDimensionEnabled) {
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

    static MountedChoice chooseMounted(DestinationSafety.HomeFit exact, boolean mayTryFallback,
                                       DestinationSafety.HomeFit fallback) {
        if (exact == DestinationSafety.HomeFit.FITS) return MountedChoice.EXACT;
        if (exact == DestinationSafety.HomeFit.UNSAFE) return MountedChoice.UNSAFE;
        if (!mayTryFallback) return MountedChoice.VEHICLE_TOO_LARGE;
        if (fallback == DestinationSafety.HomeFit.FITS) return MountedChoice.ABOVE_BED;
        if (fallback == DestinationSafety.HomeFit.UNSAFE) return MountedChoice.UNSAFE;
        return MountedChoice.VEHICLE_TOO_LARGE;
    }

    static Outcome mountedGeometryOutcome(int width, int clearHeight,
                                          Supplier<DestinationSafety.HomeFit> safety) {
        if (!DestinationSafety.rootGeometrySupported(width, clearHeight)) return Outcome.VEHICLE_TOO_LARGE;
        return safety.get() == DestinationSafety.HomeFit.FITS ? Outcome.ACCEPT : Outcome.UNSAFE;
    }

    static Result find(ServerPlayer player, boolean force, boolean crossDimensionEnabled) {
        return find(force, new MinecraftHomeAccess(player, crossDimensionEnabled));
    }

    static Result find(boolean force, HomeAccess access) {
        Validation validation = access.validate();
        if (validation.outcome() != Outcome.ACCEPT) return new Result(validation.outcome(), null);
        PreparedSavedHome prepared = access.prepare(validation.home());
        Resolution resolution = access.resolve(prepared);
        if (resolution.outcome() != Outcome.ACCEPT) return new Result(resolution.outcome(), null);
        return access.evaluate(resolution.home(), force);
    }

    static PreparedSavedHome prepare(SavedHome home, LongConsumer loader) {
        for (long chunk : DestinationSafety.destinationChunks(
                home.homeBlock().getX() + 0.5, home.homeBlock().getZ() + 0.5)) loader.accept(chunk);
        return new PreparedSavedHome(home);
    }

    static Result evaluate(ResolvedHome resolved, boolean force) {
        SavedHome home = resolved.prepared().home();
        ServerPlayer player = home.player();
        TeleportTransition respawn = resolved.respawn();
        Entity root = resolved.root();
        if (root == player) {
            if (!acceptUnmounted(force, () -> DestinationSafety.unmountedHomeFits(
                    player, respawn.newLevel(), respawn.position()))) {
                return new Result(Outcome.UNSAFE, null);
            }
            return new Result(Outcome.ACCEPT, DestinationSafety.Prepared.ordinary(
                    respawn.newLevel(), respawn.position(), respawn.yRot(), respawn.xRot()));
        }

        if (force) {
            return new Result(Outcome.ACCEPT, DestinationSafety.Prepared.ordinary(
                    respawn.newLevel(), respawn.position(), root.getYRot(), root.getXRot()));
        }

        DestinationSafety.RootGeometry geometry = DestinationSafety.rootGeometry(root);
        if (!DestinationSafety.rootGeometrySupported(geometry.width(), geometry.clearHeight())) {
            return new Result(Outcome.VEHICLE_TOO_LARGE, null);
        }

        BlockPos homeBlock = home.homeBlock();
        DestinationSafety.HomeFit exactFit = DestinationSafety.mountedHomeFit(
                root, respawn.newLevel(), respawn.position(), homeBlock);
        boolean bed = respawn.newLevel().getBlockState(homeBlock).getBlock() instanceof BedBlock;
        boolean covered = bed && isCoveredBed(respawn.newLevel(), homeBlock);
        boolean mayTryFallback = mayTryAboveBed(true, bed, home.forcedRespawn(), covered);
        Vec3 aboveBed = new Vec3(homeBlock.getX() + 0.5, homeBlock.getY() + 1.0,
                homeBlock.getZ() + 0.5);
        DestinationSafety.HomeFit fallbackFit = mayTryFallback
                ? DestinationSafety.mountedHomeFit(root, respawn.newLevel(), aboveBed, homeBlock)
                : DestinationSafety.HomeFit.BLOCKED;
        MountedChoice choice = chooseMounted(exactFit, mayTryFallback, fallbackFit);
        if (choice == MountedChoice.UNSAFE) return new Result(Outcome.UNSAFE, null);
        if (choice == MountedChoice.VEHICLE_TOO_LARGE) {
            return new Result(Outcome.VEHICLE_TOO_LARGE, null);
        }
        Vec3 position = choice == MountedChoice.ABOVE_BED ? aboveBed : respawn.position();
        return new Result(Outcome.ACCEPT, DestinationSafety.Prepared.ordinary(
                respawn.newLevel(), position, root.getYRot(), root.getXRot()));
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
            var respawnConfig = player.getRespawnConfig();
            if (respawnConfig == null) return new Validation(Outcome.NO_HOME, null);
            if (!(player.level() instanceof ServerLevel current)) return new Validation(Outcome.UNSAFE, null);
            ServerLevel savedLevel = current.getServer().getLevel(respawnConfig.respawnData().dimension());
            if (savedLevel == null) return new Validation(Outcome.NO_HOME, null);
            if (!current.dimension().equals(savedLevel.dimension()) && !crossDimensionEnabled) {
                return new Validation(Outcome.CROSS_DIMENSION, null);
            }
            return new Validation(Outcome.ACCEPT, new SavedHome(player, current, savedLevel,
                    respawnConfig.respawnData().pos(), respawnConfig.forced()));
        }

        @Override
        public PreparedSavedHome prepare(SavedHome home) {
            return HomeDestination.prepare(home, chunk -> DestinationSafety.loadChunk(home.savedLevel(), chunk));
        }

        @Override
        public Resolution resolve(PreparedSavedHome prepared) {
            // false preserves respawn-anchor charges; vanilla remains the authority for placement.
            TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(
                    false, TeleportTransition.DO_NOTHING);
            if (respawn.missingRespawnBlock()) return new Resolution(Outcome.NO_HOME, null);
            return new Resolution(Outcome.ACCEPT,
                    new ResolvedHome(prepared, respawn, player.getRootVehicle()));
        }

        @Override
        public Result evaluate(ResolvedHome resolved, boolean force) {
            return HomeDestination.evaluate(resolved, force);
        }
    }

    private static boolean isCoveredBed(ServerLevel level, BlockPos homeBlock) {
        var homeState = level.getBlockState(homeBlock);
        BlockPos other = homeBlock.relative(BedBlock.getConnectedDirection(homeState));
        return !level.getBlockState(homeBlock.above()).getCollisionShape(level, homeBlock.above()).isEmpty()
                || !level.getBlockState(other.above()).getCollisionShape(level, other.above()).isEmpty();
    }
}
