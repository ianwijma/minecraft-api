package dev.example.mapi.fabric;

import dev.example.mapi.api.PlatformType;
import dev.example.mapi.internal.MapiPlatform;
import dev.example.mapi.internal.PhysicalSide;
import dev.example.mapi.internal.RawBlockRead;
import dev.example.mapi.internal.RawPlayerSnapshot;
import dev.example.mapi.internal.RawServerInfo;
import dev.example.mapi.internal.RawWorldTime;
import dev.example.mapi.internal.ServerHandle;
import dev.example.mapi.internal.ServerLifecycleListener;
import dev.example.mapi.internal.UnknownDimensionException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric implementation of the loader-neutral platform contract.
 */
final class FabricPlatform implements MapiPlatform {

    private static final Logger LOG = LoggerFactory.getLogger("mapi");

    @Override
    public PlatformType type() {
        return PlatformType.FABRIC;
    }

    @Override
    public String platformVersion() {
        return MapiFabric.loaderVersion();
    }

    @Override
    public String minecraftVersion() {
        return MapiFabric.minecraftVersion();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public PhysicalSide physicalSide() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                ? PhysicalSide.CLIENT
                : PhysicalSide.DEDICATED_SERVER;
    }

    @Override
    public Logger logger() {
        return LOG;
    }

    @Override
    public void registerServerLifecycle(ServerLifecycleListener listener) {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                listener.onServerStarting(FabricServerHandle.starting(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> listener.onServerStopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> listener.onServerStopped());
    }

    /**
     * Server handle backed by a live {@link MinecraftServer}. Start time is
     * captured at construction (the SERVER_STARTING event).
     */
    private record FabricServerHandle(MinecraftServer server, long startedAtEpochMs) implements ServerHandle {

        private FabricServerHandle {
            java.util.Objects.requireNonNull(server, "server");
        }

        static FabricServerHandle starting(MinecraftServer server) {
            return new FabricServerHandle(server, System.currentTimeMillis());
        }

        @Override
        public void executeOnServerThread(Runnable task) {
            server.execute(task);
        }

        @Override
        public java.util.function.Supplier<RawServerInfo> infoSupplier() {
            return () -> new RawServerInfo(
                    startedAtEpochMs(),
                    server.getPlayerCount(),
                    server.getPlayerList().getMaxPlayers(),
                    server.getTickCount(),
                    server.getAverageTickTimeNanos() / 1_000_000.0d,
                    server.getMotd());
        }

        @Override
        public java.util.function.Supplier<List<RawPlayerSnapshot>> playersSupplier() {
            return () -> {
                List<RawPlayerSnapshot> players = new ArrayList<>();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    Vec3 pos = player.position();
                    players.add(new RawPlayerSnapshot(
                            player.getGameProfile().name(),
                            player.getGameProfile().id(),
                            player.level().dimension().identifier().toString(),
                            pos.x(), pos.y(), pos.z()));
                }
                return players;
            };
        }

        @Override
        public java.util.function.Supplier<RawBlockRead> blockSupplier(String dimension, int x, int y, int z) {
            return () -> {
                ServerLevel level = resolveLevel(dimension);
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.hasChunkAt(pos)) {
                    return null;
                }
                BlockState state = level.getBlockState(pos);
                Map<String, String> properties = new LinkedHashMap<>();
                for (Property<?> property : state.getProperties()) {
                    properties.put(property.getName(), propertyName(state, property));
                }
                return new RawBlockRead(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                        Map.copyOf(properties), dimension, x, y, z);
            };
        }

        @Override
        public java.util.function.Supplier<RawWorldTime> timeSupplier(String dimension) {
            return () -> {
                ServerLevel level = resolveLevel(dimension);
                return new RawWorldTime(level.getGameTime(), level.getOverworldClockTime(),
                        level.getDefaultClockTime());
            };
        }

        @Override
        public java.util.function.Supplier<Integer> dataVersionSupplier() {
            return () -> server.getWorldData().getVersion();
        }

        @Override
        public java.util.function.Supplier<dev.example.mapi.internal.RawCommandResult> commandSupplier(
                String command, int permissionLevel) {
            return () -> {
                List<String> feedback = new ArrayList<>();
                AtomicReference<Integer> result = new AtomicReference<>();
                AtomicReference<Boolean> success = new AtomicReference<>();
                net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
                    @Override
                    public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                        feedback.add(message.getString());
                    }

                    @Override
                    public boolean acceptsSuccess() {
                        return true;
                    }

                    @Override
                    public boolean acceptsFailure() {
                        return true;
                    }

                    @Override
                    public boolean shouldInformAdmins() {
                        return false;
                    }
                };
                net.minecraft.commands.CommandSourceStack source = server.createCommandSourceStack()
                        .withSource(capture)
                        .withPermission(permissionSet(permissionLevel));
                net.minecraft.commands.CommandResultCallback callback = (ok, value) -> {
                    success.set(ok);
                    result.set(value);
                };
                server.getCommands().performPrefixedCommand(source.withCallback(callback), command);
                return new dev.example.mapi.internal.RawCommandResult(result.get(), success.get(),
                        List.copyOf(feedback));
            };
        }

        private static net.minecraft.server.permissions.PermissionSet permissionSet(int level) {
            int clamped = java.lang.Math.clamp(level, 0, 4);
            return switch (clamped) {
                case 0 -> net.minecraft.server.permissions.LevelBasedPermissionSet.ALL;
                case 1 -> net.minecraft.server.permissions.LevelBasedPermissionSet.MODERATOR;
                case 2 -> net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER;
                case 3 -> net.minecraft.server.permissions.LevelBasedPermissionSet.ADMIN;
                default -> net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER;
            };
        }

        private ServerLevel resolveLevel(String dimension) {
            if (dimension != null && !dimension.isBlank()) {
                ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        Identifier.parse(dimension.trim()));
                ServerLevel level = server.getLevel(key);
                if (level != null) {
                    return level;
                }
            }
            throw new UnknownDimensionException("Unknown dimension: " + dimension);
        }

        private static <T extends Comparable<T>> String propertyName(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }
    }
}
