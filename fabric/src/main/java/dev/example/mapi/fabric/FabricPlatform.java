package dev.example.mapi.fabric;

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
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
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
    public List<RawModInfo> mods() {
        List<RawModInfo> mods = new ArrayList<>();
        for (var container : FabricLoader.getInstance().getAllMods()) {
            mods.add(new RawModInfo(
                    container.getMetadata().getId(),
                    container.getMetadata().getName(),
                    container.getMetadata().getVersion().getFriendlyString()));
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

        @Override
        public java.util.function.Supplier<dev.example.mapi.internal.RawBlockEntityRead> blockEntitySupplier(
                String dimension, int x, int y, int z) {
            return () -> {
                ServerLevel level = resolveLevel(dimension);
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.hasChunkAt(pos)) {
                    return dev.example.mapi.internal.RawBlockEntityRead.unloaded();
                }
                net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(pos);
                if (entity == null) {
                    return dev.example.mapi.internal.RawBlockEntityRead.absent();
                }
                net.minecraft.nbt.CompoundTag nbt = entity.saveWithFullMetadata(level.registryAccess());
                return new dev.example.mapi.internal.RawBlockEntityRead(true,
                        new dev.example.mapi.internal.RawBlockEntity(
                                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(),
                                dimension, x, y, z, nbtToJson(nbt)));
            };
        }

        @Override
        public java.util.function.Supplier<dev.example.mapi.internal.RawStorageRead> storageSupplier(
                String dimension, int x, int y, int z) {
            return () -> {
                ServerLevel level = resolveLevel(dimension);
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.hasChunkAt(pos)) {
                    return dev.example.mapi.internal.RawStorageRead.unloaded();
                }
                net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(pos);
                if (!(entity instanceof net.minecraft.world.Container container)) {
                    return dev.example.mapi.internal.RawStorageRead.notContainer(entity == null
                            ? null
                            : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
                }
                List<dev.example.mapi.internal.RawStorageSnapshot.Slot> slots = new ArrayList<>();
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    net.minecraft.world.item.ItemStack stack = container.getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    slots.add(new dev.example.mapi.internal.RawStorageSnapshot.Slot(slot,
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
                }
                String typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString();
                return new dev.example.mapi.internal.RawStorageRead(true, typeId,
                        new dev.example.mapi.internal.RawStorageSnapshot(typeId, List.copyOf(slots),
                                container.getContainerSize()));
            };
        }

        private static Map<String, Object> nbtToJson(net.minecraft.nbt.CompoundTag tag) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, net.minecraft.nbt.Tag> entry : tag.entrySet()) {
                out.put(entry.getKey(), tagToJson(entry.getValue()));
            }
            return out;
        }

        private static Object tagToJson(net.minecraft.nbt.Tag tag) {
            if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
                return nbtToJson(compound);
            }
            if (tag instanceof net.minecraft.nbt.ListTag list) {
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    items.add(tagToJson(list.get(i)));
                }
                return Map.of("list", items);
            }
            if (tag instanceof net.minecraft.nbt.ByteArrayTag array) {
                List<Object> items = new ArrayList<>();
                for (byte b : array.getAsByteArray()) {
                    items.add(b);
                }
                return Map.of("ba", items);
            }
            if (tag instanceof net.minecraft.nbt.IntArrayTag array) {
                List<Object> items = new ArrayList<>();
                for (int i : array.getAsIntArray()) {
                    items.add(i);
                }
                return Map.of("ia", items);
            }
            if (tag instanceof net.minecraft.nbt.LongArrayTag array) {
                List<Object> items = new ArrayList<>();
                for (long l : array.getAsLongArray()) {
                    items.add(String.valueOf(l));
                }
                return Map.of("la", items);
            }
            if (tag instanceof net.minecraft.nbt.NumericTag numeric) {
                if (tag instanceof net.minecraft.nbt.ByteTag byteTag) {
                    return Map.of("b", byteTag.byteValue());
                }
                if (tag instanceof net.minecraft.nbt.ShortTag shortTag) {
                    return Map.of("s", shortTag.shortValue());
                }
                if (tag instanceof net.minecraft.nbt.LongTag longTag) {
                    return Map.of("l", String.valueOf(longTag.longValue()));
                }
                if (tag instanceof net.minecraft.nbt.FloatTag floatTag) {
                    return Map.of("f", String.valueOf(floatTag.floatValue()));
                }
                if (tag instanceof net.minecraft.nbt.IntTag intTag) {
                    return intTag.intValue();
                }
                return numeric.doubleValue();
            }
            if (tag instanceof net.minecraft.nbt.StringTag stringTag) {
                return stringTag.value();
            }
            return tag.toString();
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
