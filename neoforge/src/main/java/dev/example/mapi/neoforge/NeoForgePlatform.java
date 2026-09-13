package dev.example.mapi.neoforge;

import dev.example.mapi.api.PlatformType;
import dev.example.mapi.internal.MapiPlatform;
import dev.example.mapi.internal.PhysicalSide;
import dev.example.mapi.internal.RawBlockRead;
import dev.example.mapi.internal.RawCommandResult;
import dev.example.mapi.internal.RawModInfo;
import dev.example.mapi.internal.RawPlayerSnapshot;
import dev.example.mapi.internal.RawServerInfo;
import dev.example.mapi.internal.RawWorldTime;
import dev.example.mapi.internal.ServerHandle;
import dev.example.mapi.internal.ServerHandle.RegistryIdPage;
import dev.example.mapi.internal.ServerLifecycleListener;
import dev.example.mapi.internal.UnknownDimensionException;
import dev.example.mapi.internal.UnknownRegistryTypeException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge implementation of the loader-neutral platform contract.
 */
final class NeoForgePlatform implements MapiPlatform {

    private static final Logger LOG = LoggerFactory.getLogger("mapi");

    @Override
    public PlatformType type() {
        return PlatformType.NEOFORGE;
    }

    @Override
    public String platformVersion() {
        return ModList.get().getModContainerById("neoforge")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public String minecraftVersion() {
        return ModList.get().getModContainerById("minecraft")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public PhysicalSide physicalSide() {
        return FMLEnvironment.getDist() == Dist.CLIENT
                ? PhysicalSide.CLIENT
                : PhysicalSide.DEDICATED_SERVER;
    }

    @Override
    public List<RawModInfo> mods() {
        List<RawModInfo> mods = new ArrayList<>();
        for (var info : ModList.get().getMods()) {
            mods.add(new RawModInfo(info.getModId(), info.getDisplayName(), info.getVersion().toString()));
        }
        mods.sort(java.util.Comparator.comparing(RawModInfo::id));
        return List.copyOf(mods);
    }

    @Override
    public Logger logger() {
        return LOG;
    }

    @Override
    public void registerServerLifecycle(ServerLifecycleListener listener) {
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                listener.onServerStarting(NeoForgeServerHandle.starting(event.getServer())));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> listener.onServerStopping());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> listener.onServerStopped());
    }

    /**
     * Server handle backed by a live {@link MinecraftServer}. Start time is
     * captured at construction (the ServerStartingEvent).
     */
    private record NeoForgeServerHandle(MinecraftServer server, long startedAtEpochMs) implements ServerHandle {

        private NeoForgeServerHandle {
            java.util.Objects.requireNonNull(server, "server");
        }

        static NeoForgeServerHandle starting(MinecraftServer server) {
            return new NeoForgeServerHandle(server, System.currentTimeMillis());
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

        private static Registry<?> registryFor(String type) {
            return switch (type) {
                case "block" -> BuiltInRegistries.BLOCK;
                case "item" -> BuiltInRegistries.ITEM;
                case "entity_type" -> BuiltInRegistries.ENTITY_TYPE;
                case "block_entity_type" -> BuiltInRegistries.BLOCK_ENTITY_TYPE;
                case "fluid" -> BuiltInRegistries.FLUID;
                case "sound_event" -> BuiltInRegistries.SOUND_EVENT;
                case "mob_effect" -> BuiltInRegistries.MOB_EFFECT;
                case "attribute" -> BuiltInRegistries.ATTRIBUTE;
                default -> throw new UnknownRegistryTypeException(
                        "Unknown registry type: " + type + " (supported: block, item, entity_type, "
                                + "block_entity_type, fluid, sound_event, mob_effect, attribute)");
            };
        }

        @Override
        public java.util.function.Supplier<RegistryIdPage> registryIdsSupplier(String type, int limit,
                int offset) {
            return () -> {
                List<String> ids = registryFor(type).keySet().stream().map(Identifier::toString)
                        .sorted().toList();
                int from = Math.min(offset, ids.size());
                int to = Math.min(offset + limit, ids.size());
                return new RegistryIdPage(ids.subList(from, to), ids.size());
            };
        }

        @Override
        public java.util.function.Supplier<List<String>> tagIdsSupplier(String type) {
            return () -> registryFor(type).listTagIds()
                    .map(tagKey -> tagKey.location().toString())
                    .sorted()
                    .toList();
        }

        @Override
        public java.util.function.Supplier<List<String>> tagMembersSupplier(String type, String tagId) {
            return () -> tagMembers(registryFor(type), tagId);
        }

        private static <T> List<String> tagMembers(Registry<T> registry, String tagId) {
            TagKey<T> tagKey = TagKey.create(registry.key(), Identifier.parse(tagId));
            List<String> members = new ArrayList<>();
            for (Holder<T> holder : registry.getTagOrEmpty(tagKey)) {
                members.add(holder.getRegisteredName());
            }
            return members.stream().sorted().toList();
        }
    }
}
