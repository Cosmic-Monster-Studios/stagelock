package dev.stagelock.core.discovery;

import dev.stagelock.network.SyncDiscoveredMobsPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells each client which mobs its player has killed at least once, for the recipe viewer option
 * {@code viewers.mobLootDiscovery}. The vanilla "killed" statistic is the only record: nothing extra is
 * saved, and mobs killed before the option was turned on already count.
 * <p>
 * Clients are told whether or not the option is on, so switching it on needs no relog.
 */
public final class MobDiscovery {

    private record Kill(UUID player, EntityType<?> mob) {
    }

    /** Kills whose statistic vanilla is about to award; checked at the end of the tick. */
    private static final List<Kill> PENDING = new ArrayList<>();
    /** What each online player's client has been told. */
    private static final Map<UUID, Set<EntityType<?>>> SENT = new HashMap<>();

    private MobDiscovery() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) syncAll(player);
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> SENT.remove(event.getEntity().getUUID()));
        bus.addListener(EventPriority.LOWEST, LivingDeathEvent.class, MobDiscovery::onDeath);
        bus.addListener(ServerTickEvent.Post.class, event -> flush(event.getServer()));
        bus.addListener(ServerStoppedEvent.class, event -> {
            PENDING.clear();
            SENT.clear();
        });
    }

    /** Every mob the player's statistics say they have killed. */
    public static Set<EntityType<?>> discovered(ServerPlayer player) {
        Set<EntityType<?>> mobs = new HashSet<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (hasKilled(player, type)) mobs.add(type);
        }
        return mobs;
    }

    public static boolean hasKilled(ServerPlayer player, EntityType<?> type) {
        return player.getStats().getValue(Stats.ENTITY_KILLED.get(type)) > 0;
    }

    /** The mobs the player's client has been told about; empty for a client that can't be told. */
    public static Set<EntityType<?>> sentTo(ServerPlayer player) {
        return Set.copyOf(SENT.getOrDefault(player.getUUID(), Set.of()));
    }

    /** Sends the player's whole list, replacing whatever their client had. */
    public static void syncAll(ServerPlayer player) {
        if (!player.connection.hasChannel(SyncDiscoveredMobsPayload.TYPE)) return;
        Set<EntityType<?>> mobs = discovered(player);
        SENT.put(player.getUUID(), mobs);
        PacketDistributor.sendToPlayer(player, new SyncDiscoveredMobsPayload(true, ids(mobs)));
    }

    /** Vanilla awards the statistic right after this event, so the kill is only noted here. */
    private static void onDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            Set<EntityType<?>> sent = SENT.get(player.getUUID());
            EntityType<?> mob = event.getEntity().getType();
            if (sent != null && !sent.contains(mob)) PENDING.add(new Kill(player.getUUID(), mob));
        }
    }

    private static void flush(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        List<Kill> kills = List.copyOf(PENDING);
        PENDING.clear();
        for (Kill kill : kills) {
            ServerPlayer player = server.getPlayerList().getPlayer(kill.player());
            Set<EntityType<?>> sent = SENT.get(kill.player());
            // Another mod may have cancelled the death, in which case no statistic was awarded.
            if (player == null || sent == null || !hasKilled(player, kill.mob()) || !sent.add(kill.mob())) continue;
            PacketDistributor.sendToPlayer(player, new SyncDiscoveredMobsPayload(false, ids(Set.of(kill.mob()))));
        }
    }

    private static Set<ResourceLocation> ids(Set<EntityType<?>> mobs) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (EntityType<?> mob : mobs) ids.add(BuiltInRegistries.ENTITY_TYPE.getKey(mob));
        return ids;
    }
}
