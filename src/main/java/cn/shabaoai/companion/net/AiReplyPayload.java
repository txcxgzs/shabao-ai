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
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record AiReplyPayload(String text) implements CustomPayload {
    public static final Id<AiReplyPayload> ID=new Id<>(Identifier.of(ShabaoAiMod.MOD_ID,"reply"));
    public static final PacketCodec<RegistryByteBuf,AiReplyPayload> CODEC=PacketCodec.tuple(PacketCodecs.STRING,AiReplyPayload::text,AiReplyPayload::new);
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
