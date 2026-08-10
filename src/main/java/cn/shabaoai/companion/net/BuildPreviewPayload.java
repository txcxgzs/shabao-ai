/*
 * Copyright (C) 2026 txcxgzs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package cn.shabaoai.companion.net;

import cn.shabaoai.companion.ShabaoAiMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 建筑预览网络包：服务端在确认前把即将放置的方块位置发给客户端，
 * 客户端用绿色半透明线框在世界中渲染出来。
 */
public record BuildPreviewPayload(List<BlockPos> positions, boolean clear) implements CustomPayload {
    public static final Id<BuildPreviewPayload> ID = new Id<>(Identifier.of(ShabaoAiMod.MOD_ID, "build_preview"));

    /** 自定义编解码器：先写 boolean clear，再写位置数量，然后逐个写 BlockPos */
    public static final PacketCodec<RegistryByteBuf, BuildPreviewPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeBoolean(payload.clear);
                buf.writeVarInt(payload.positions.size());
                for (BlockPos pos : payload.positions) {
                    buf.writeBlockPos(pos);
                }
            },
            buf -> {
                boolean clear = buf.readBoolean();
                int size = buf.readVarInt();
                List<BlockPos> positions = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    positions.add(buf.readBlockPos());
                }
                return new BuildPreviewPayload(positions, clear);
            }
    );

    /** 空包：用于清除客户端预览 */
    public static BuildPreviewPayload empty() {
        return new BuildPreviewPayload(List.of(), true);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
