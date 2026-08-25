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

package cn.shabaoai.companion.build;

import cn.shabaoai.companion.config.ModConfig;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildManager {
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, SavedPlan> previous = new ConcurrentHashMap<>();

    public void start(ServerPlayerEntity player, BuildPlan plan) {
        // 【BUG-02】生存模式建造门控：/ai build 命令路径，非创造模式且未开启 allowSurvivalBuilding 时拒绝。
        if (!player.isCreative() && !ModConfig.get().allowSurvivalBuilding) {
            player.sendMessage(Text.literal("§c当前为生存模式且未开启生存建造（allowSurvivalBuilding=false），已拒绝。请在配置中开启或切换创造模式。"));
            return;
        }
        if (plan.placements().size() > ModConfig.get().maxBuildBlocks) {
            player.sendMessage(Text.literal("§c建筑超过安全上限。")); return;
        }
        ServerWorld world = player.getServerWorld();
        Optional<BuildPlan.Placement> collision = plan.placements().stream().filter(p -> !canPlace(world, p)).findFirst();
        if (collision.isPresent()) {
            BlockPos pos = collision.get().pos();
            player.sendMessage(Text.literal("§c建造区域被现有方块占用：" + pos.toShortString() + "。为避免破坏世界，任务已取消。"));
            return;
        }
        cancel(player.getUuid());
        jobs.put(player.getUuid(), new Job(player.getUuid(), world.getRegistryKey(), plan, new ArrayDeque<>(plan.placements())));
        previous.put(player.getUuid(), new SavedPlan(world.getRegistryKey(), plan));
        player.sendMessage(Text.literal("§b沙包：开始建造 " + plan.name() + "（" + plan.placements().size() + " 方块）"));
    }

    public boolean clearLast(ServerPlayerEntity player) {
        cancel(player.getUuid());
        SavedPlan old=previous.remove(player.getUuid());
        if(old==null)return false;
        ServerWorld world=player.getServer().getWorld(old.world());
        if(world==null)return false;
        clear(world,old.plan());
        // 假玩家没有 buildTarget 概念，无需清理
        return true;
    }

    private void clear(ServerWorld world, BuildPlan plan) {
        for(BuildPlan.Placement p:plan.placements()) if(world.getBlockState(p.pos()).equals(p.state())) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK,p.state()),p.pos().getX()+.5,p.pos().getY()+.5,p.pos().getZ()+.5,4,.3,.3,.3,.05);
            world.breakBlock(p.pos(),false);
        }
    }

    public void cancel(UUID owner) {
        jobs.remove(owner);
    }

    public void tick(MinecraftServer server) {
        int count=Math.max(1,ModConfig.get().blocksPerTick);
        Iterator<Job> it=jobs.values().iterator();
        while(it.hasNext()) {
            Job job=it.next(); ServerPlayerEntity player=server.getPlayerManager().getPlayer(job.owner);
            if(player==null){it.remove();continue;}
            ServerWorld world=server.getWorld(job.world);
            if(world==null){it.remove();continue;}
            // 【BUG-02】每 tick 复查游戏模式：start 放行后玩家可能切到生存/旁观，
            // 此时不应继续凭空放置方块。与 start 入口门控条件一致。
            if(!player.isCreative() && !ModConfig.get().allowSurvivalBuilding){
                player.sendMessage(Text.literal("§c游戏模式变更，建造已中止。"));
                it.remove();
                continue;
            }
            // 【L9】导航目标每 tick 只更新一次（指向当前队首），
            // 不要每放一块就 startMovingTo 一次——高频重置导航点会让寻路反复重算
            BuildPlan.Placement next=job.queue.peekFirst();
            if(next!=null) cn.shabaoai.companion.entity.CompanionManager.moveTo(player,next.pos());
            for(int i=0;i<count&&!job.queue.isEmpty();i++) {
                BuildPlan.Placement p=job.queue.removeFirst();
                if(world.isInBuildLimit(p.pos())) {
                    world.setBlockState(p.pos(),p.state(),3);
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK,p.state()),p.pos().getX()+.5,p.pos().getY()+.5,p.pos().getZ()+.5,3,.25,.25,.25,.03);
                }
            }
            if(job.queue.isEmpty()){
                player.sendMessage(Text.literal("§a沙包：建好啦！"));it.remove();
            }
        }
    }
    private static boolean canPlace(ServerWorld world, BuildPlan.Placement placement) {
        var current=world.getBlockState(placement.pos());
        return current.isAir() || current.isReplaceable();
    }
    private record SavedPlan(RegistryKey<World> world, BuildPlan plan) {}
    private record Job(UUID owner, RegistryKey<World> world, BuildPlan plan, ArrayDeque<BuildPlan.Placement> queue) {}
}
