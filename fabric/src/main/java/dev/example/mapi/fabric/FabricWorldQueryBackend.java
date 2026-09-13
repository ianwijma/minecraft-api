package dev.example.mapi.fabric;

import com.mojang.authlib.GameProfile;
import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.query.WorldQueryBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric world-query backend over the verified 26.2 server APIs. Executes on
 * the server thread only. Queries never generate chunks; only loaded
 * entities/levels are observable.
 */
final class FabricWorldQueryBackend implements WorldQueryBackend {

    private final MinecraftServer server;

    FabricWorldQueryBackend(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public List<PlayerRecord> players(int max) {
        List<PlayerRecord> records = new ArrayList<>(Math.min(max, server.getPlayerCount()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (records.size() >= max) {
                break;
            }
            GameProfile profile = player.getGameProfile();
            Vec3 pos = player.position();
            List<ItemRecord> inventory = new ArrayList<>();
            var inv = player.getInventory();
            for (int slot = 0; slot < inv.getContainerSize() && inventory.size() < 64; slot++) {
                ItemStack stack = inv.getItem(slot);
                if (!stack.isEmpty()) {
                    inventory.add(new ItemRecord(slot, id(BuiltInRegistries.ITEM.getKey(stack.getItem())),
                            stack.getCount()));
                }
            }
            records.add(new PlayerRecord(profile.id().toString(), profile.name(),
                    dimension(player.level().dimension()), pos.x, pos.y, pos.z, inventory));
        }
        return records;
    }

    @Override
    public List<EntityRecord> entities(String dimension, double cx, double cy, double cz,
            int radius, int max) {
        ServerLevel level = level(dimension);
        if (level == null) {
            return List.of();
        }
        double radiusSq = (double) radius * radius;
        List<EntityRecord> records = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (records.size() >= max) {
                break;
            }
            Vec3 pos = entity.position();
            double dx = pos.x - cx;
            double dy = pos.y - cy;
            double dz = pos.z - cz;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                records.add(new EntityRecord(entity.getUUID().toString(),
                        id(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())),
                        dimension, pos.x, pos.y, pos.z));
            }
        }
        return records;
    }

    @Override
    public Optional<BlockRecord> block(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) {
            return Optional.empty();
        }
        BlockPos pos = new BlockPos(x, y, z);
        String blockId = id(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return Optional.of(new BlockRecord(blockId, Optional.empty(), Optional.empty()));
        }
        String typeId = id(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
        Tag data = NbtTags.toTag(blockEntity.saveWithFullMetadata(level.registryAccess()));
        return Optional.of(new BlockRecord(blockId, Optional.of(typeId), Optional.of(data)));
    }

    @Override
    public List<RegistrySummary> registries() {
        List<RegistrySummary> summaries = new ArrayList<>();
        for (Registry<?> registry : registriesOfRegistries()) {
            Identifier key = registriesOfRegistries().getKey(registry);
            if (key != null) {
                summaries.add(new RegistrySummary(id(key), registry.keySet().size()));
            }
        }
        return summaries;
    }

    @Override
    public List<String> registryEntries(String registryId, int max) {
        for (Registry<?> registry : registriesOfRegistries()) {
            Identifier key = registriesOfRegistries().getKey(registry);
            if (key != null && id(key).equals(registryId)) {
                return registry.keySet().stream()
                        .map(Identifier::toString)
                        .sorted()
                        .limit(max)
                        .toList();
            }
        }
        return List.of();
    }

    @Override
    public boolean canQueryDimension(String dimension) {
        return level(dimension) != null;
    }

    private ServerLevel level(String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (dimension(level.dimension()).equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Registry<Registry<?>> registriesOfRegistries() {
        return (Registry<Registry<?>>) (Object) BuiltInRegistries.REGISTRY;
    }

    private static String dimension(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
        return key.identifier().toString();
    }

    private static String id(Identifier identifier) {
        return identifier.toString();
    }
}
