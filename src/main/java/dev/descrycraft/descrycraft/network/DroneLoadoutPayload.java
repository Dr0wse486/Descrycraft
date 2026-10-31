package dev.descrycraft.descrycraft.network;

import dev.descrycraft.descrycraft.Descrycraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "switch this fitted module on or off at the loadout screen".
 *
 * <p>Only the intent travels, never the resulting mask. The rule that decides what a toggle
 * actually does - prerequisites, cascading shutdowns - is evaluated on both sides from the same
 * data, so the client can answer instantly and the server still has the final say. Sending the
 * mask instead would let a modified client hand itself any loadout it liked.
 *
 * @param offHand whether the drone being configured is in the off hand
 * @param flag    the single module bit being flipped
 * @param enabled the state the player is asking for
 */
public record DroneLoadoutPayload(boolean offHand, int flag, boolean enabled)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DroneLoadoutPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Descrycraft.MOD_ID, "drone_loadout"));

    public static final StreamCodec<ByteBuf, DroneLoadoutPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, DroneLoadoutPayload::offHand,
                    ByteBufCodecs.VAR_INT, DroneLoadoutPayload::flag,
                    ByteBufCodecs.BOOL, DroneLoadoutPayload::enabled,
                    DroneLoadoutPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
