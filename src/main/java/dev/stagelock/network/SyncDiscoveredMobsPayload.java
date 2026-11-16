package dev.stagelock.network;

import dev.stagelock.StageLock;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * Server → client: entity types the player has killed at least once. {@code replace} sends the whole
 * list (on login); otherwise {@code mobs} are added to what the client already knows.
 */
public record SyncDiscoveredMobsPayload(boolean replace, Set<ResourceLocation> mobs) implements CustomPacketPayload {

    public static final Type<SyncDiscoveredMobsPayload> TYPE = new Type<>(StageLock.id("sync_discovered_mobs"));

    public static final StreamCodec<ByteBuf, SyncDiscoveredMobsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncDiscoveredMobsPayload::replace,
            ByteBufCodecs.collection(HashSet::new, ResourceLocation.STREAM_CODEC), SyncDiscoveredMobsPayload::mobs,
            SyncDiscoveredMobsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
