package dev.stagelock.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.HashSet;
import java.util.Set;

/**
 * The mobs the local player has killed at least once, as last synced by the server. Only recipe
 * viewers use it, for the server option {@code viewers.mobLootDiscovery}.
 * <p>
 * Contains no client-only class references in its signatures, so common code may call it.
 */
public final class MobDiscoveries {

    private static volatile Set<ResourceLocation> discovered = Set.of();

    private MobDiscoveries() {
    }

    /** An immutable set that is replaced, never changed, when the server sends more. */
    public static Set<ResourceLocation> get() {
        return discovered;
    }

    public static boolean has(EntityType<?> type) {
        return discovered.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    /** Called on the main thread by the payload handler. */
    public static void apply(boolean replace, Set<ResourceLocation> mobs) {
        if (replace) {
            discovered = Set.copyOf(mobs);
        } else if (!discovered.containsAll(mobs)) {
            Set<ResourceLocation> merged = new HashSet<>(discovered);
            merged.addAll(mobs);
            discovered = Set.copyOf(merged);
        }
    }

    public static void reset() {
        discovered = Set.of();
    }
}
