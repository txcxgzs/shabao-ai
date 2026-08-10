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

package cn.shabaoai.companion.ai.skill;

import cn.shabaoai.companion.ai.EpisodicMemory;
import com.google.gson.JsonObject;

import cn.shabaoai.companion.ai.AgentLogger;
import cn.shabaoai.companion.ai.GoalManager;
import cn.shabaoai.companion.ai.SkillManager;
import cn.shabaoai.companion.entity.CompanionManager;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 陪伴插火把技能（0 次 LLM 调用）：玩家在昏暗洞穴时，自动在玩家附近地面放置火把照明。
 *
 * <p><b>设计意图</b>：洞穴探索时插火把是"固定规则"的重复劳动，不该占用 LLM 思考。
 * 本技能由 start_skill 显式启用（{@link #enabledByDefault()} 返回 false），
 * <b>不要求玩家持有 goal</b>——只要玩家在线且技能已启用就会工作。
 *
 * <p><b>触发条件</b>（全部满足才放置，任一不满足静默跳过，下次 tick 再试）：
 * <ol>
 *   <li>火把来源：生存/冒险模式需背包有火把（放置后<b>扣减背包数量</b>，不会凭空
 *       无限生成；无火把时按节流提示一次）；创造模式<b>凭空照明</b>——不检查背包、
 *       放置后不消耗（玩家测试创造模式时身上没火把也能正常触发）</li>
 *   <li>看不到天空（{@code !isSkyVisible}，排除露天大白天）</li>
 *   <li>环境暗：天空光 &lt; 9 且 方块光 &lt; 7（亮堂的矿洞/已插过火把的区域不再插）</li>
 *   <li>找到合法放置点：玩家周围 5×5 地面格，目标格为空气、下方是完整不透明方块、
 *       且格内无实体占用（不插到玩家/怪物身上）</li>
 *   <li>距上次放置点 &gt; 6 格（防抖，避免同一格反复插）</li>
 * </ol>
 *
 * <p><b>播报策略（P1-静默放置）</b>：成功插火把【完全静默】——插火把是"走路"级别的
 * 基础动作，不该每次邀功播报。只保留"生存模式没带火把"的<b>低频提醒</b>（90 秒冷却），
 * 避免玩家以为技能坏了又不会被刷屏。真正的陪伴感来自环境判断与自然聊天，不是基础动作播报。
 *
 * <p>线程安全：{@code lastTorchPos}/{@code lastNoTorchWarnAt} 按玩家维度记录
 * （技能实例为单例、多玩家共享，不能用裸字段互相覆盖）；
 * run 由服务端主线程 tick 驱动。
 */
public final class TorchAssistSkill implements SkillManager.Skill {
    /** 技能唯一 ID */
    private static final String ID = "torch_place";
    /** 两次插火把的最小间距（格）：距上次放置点 &gt;6 格才插（防抖） */
    private static final double MIN_SPACING = 6.0;
    /** "没带火把"提醒冷却（ms）：90 秒一次，低频不刷屏（与成功放置完全无关） */
    private static final long NO_TORCH_WARN_MS = 90_000;
    /** 放置点搜索半径（格）：玩家周围 5×5 地面格 */
    private static final int SEARCH_RADIUS = 2;
    /** 天空光阈值：低于该值视为"没有自然光"（洞穴环境） */
    private static final int SKY_LIGHT_THRESHOLD = 9;
    /** 方块光阈值：低于该值视为"暗，需要照明" */
    private static final int BLOCK_LIGHT_THRESHOLD = 7;

    /** 玩家 UUID → 上次放置火把的位置（防重复插） */
    private final Map<UUID, BlockPos> lastTorchPos = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次"没带火把"提醒时间戳（低频提醒防刷屏，与成功放置无关） */
    private final Map<UUID, Long> lastNoTorchWarnAt = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void clearPlayerState() {
        // 世界退出/服务器关闭：清空放置点/节流缓存，防止旧世界状态残留
        lastTorchPos.clear();
        lastNoTorchWarnAt.clear();
    }

    @Override
    public void clearPlayerState(UUID player) {
        lastTorchPos.remove(player);
        lastNoTorchWarnAt.remove(player);
    }

    @Override
    public SkillManager.Channel channel() {
        // 便利通道：插火把可与移动/观察通道并行（跟随 + 插火把同一 tick 同时工作）
        return SkillManager.Channel.UTILITY;
    }

    @Override
    public int priority() {
        // 优先级约定：跟随50 > 插火把40 > 闲聊10
        return 40;
    }

    @Override
    public boolean enabledByDefault() {
        // 插火把不是每个人都想要的行为：需 start_skill 显式启用
        return false;
    }

    @Override
    public boolean shouldRun(ServerWorld world, GoalManager goals, UUID player) {
        // 玩家在线即可（显式启用的技能不要求玩家持有 goal）
        return world.getServer().getPlayerManager().getPlayer(player) != null;
    }

    @Override
    public void run(ServerWorld world, GoalManager goals, UUID player) {
        // 内部兜底：任何异常只记日志，不得抛给 SkillManager 的 tick 循环
        try {
            doRun(world, player);
        } catch (Exception e) {
            AgentLogger.logError(0, "插火把技能异常: 玩家=" + player + " 原因=" + e);
        }
    }

    /** 插火把主逻辑：条件全满足才放置，任一不满足则静默跳过（下次 tick 再试） */
    private void doRun(ServerWorld world, UUID player) {
        ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(player);
        if (p == null) return;
        // 【P1-Creative 适配】创造模式：凭空照明，不要求背包有火把、放置后也不消耗。
        // 生存/冒险模式：必须有火把；没有时【不永久静默】——低频提醒"我没带火把"，
        // 独立冷却（NO_TORCH_WARN_MS）防刷屏，与成功放置完全无关。
        boolean creative = p.isCreative();
        if (!creative && p.getInventory().count(Items.TORCH) <= 0) {
            warnNoTorch(p);
            return;
        }
        // b) 环境暗：天空光与方块光都低才需要照明
        BlockPos pos = p.getBlockPos();
        if (world.getLightLevel(LightType.SKY, pos) >= SKY_LIGHT_THRESHOLD
                || world.getLightLevel(LightType.BLOCK, pos) >= BLOCK_LIGHT_THRESHOLD) return;
        // c) 看不到天空（排除露天场景：露天白天/夜晚都不该乱插火把）
        if (world.isSkyVisible(pos)) return;
        // d) 找到合法放置点（空气 + 下方完整方块 + 无实体）
        BlockPos spot = findSpot(world, p);
        if (spot == null) return;
        // e) 距上次放置点 >6 格（从未放置则通过）
        BlockPos last = lastTorchPos.get(player);
        if (last != null && dist3d(p.getBlockPos(), last) <= MIN_SPACING) return;
        // 全部满足：放置火把。只有放置成功（setBlockState 返回 true）才扣减背包 + 记录放置点。
        // 【P1-静默放置】成功插火把【完全静默】：插火把是"走路"级别的基础动作，
        // 不该每次邀功播报（"啪——插了个火把"30 秒一循环的机器人感）。
        // 陪伴感来自环境判断与自然聊天（Safety/后台认知），不是基础动作播报。
        if (world.setBlockState(spot, Blocks.TORCH.getDefaultState())) {
            if (!creative) consumeTorch(p); // 创造模式凭空生成，不扣背包
            lastTorchPos.put(player, spot);
            JsonObject details = new JsonObject();
            details.addProperty("placed_x", spot.getX());
            details.addProperty("placed_y", spot.getY());
            details.addProperty("placed_z", spot.getZ());
            details.addProperty("sky_light", world.getLightLevel(LightType.SKY, pos));
            details.addProperty("block_light", world.getLightLevel(LightType.BLOCK, pos));
            EpisodicMemory.get().record(p, EpisodicMemory.Type.ACTION, "torch_place",
                    EpisodicMemory.Source.LOCAL_SKILL, null, "success", null, details);
        }
    }

    /** 低频提醒"没带火把"：独立冷却（与成功放置无关，60~120 秒一次防刷屏） */
    private void warnNoTorch(ServerPlayerEntity p) {
        long now = System.currentTimeMillis();
        Long last = lastNoTorchWarnAt.get(p.getUuid());
        if (last != null && now - last < NO_TORCH_WARN_MS) return;
        lastNoTorchWarnAt.put(p.getUuid(), now);
        CompanionManager.sendMessage(p, "我没带火把，没法继续照明。给我点火把放背包里吧。",
                EpisodicMemory.Source.LOCAL_SKILL);
    }

    /**
     * 搜索合法火把放置点：从玩家脚层向外逐环扫描（优先近处），
     * 目标格必须是空气、下方一格是完整不透明方块、格内无实体占用。
     * 只在地面层（与玩家脚同高）找——火把插地上最稳，不做墙面火把。
     *
     * @return 合法放置点；找不到返回 null
     */
    private static BlockPos findSpot(ServerWorld world, ServerPlayerEntity p) {
        BlockPos feet = p.getBlockPos();
        // 地面层 = 玩家脚所在层；搜索 dx/dz ∈ [-2,2] 的 5×5 范围
        for (int ring = 1; ring <= SEARCH_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // 只处理当前环（外圈曼哈顿环），保证从近到远
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    BlockPos spot = feet.add(dx, 0, dz);
                    // 越界保护（超世界高度范围直接跳过）
                    if (spot.getY() < world.getBottomY() || spot.getY() > world.getTopY()) continue;
                    // 目标格必须是空气
                    if (!world.getBlockState(spot).isAir()) continue;
                    // 下方必须是完整不透明方块（火把有地面可插，不悬空/不插水中岩浆上）
                    BlockPos below = spot.down();
                    if (below.getY() < world.getBottomY()) continue;
                    if (!world.getBlockState(below).isOpaqueFullCube(world, below)) continue;
                    // 格内不能有实体（不插到玩家/怪物/掉落物上）
                    if (!world.getOtherEntities(null, new Box(spot)).isEmpty()) continue;
                    return spot;
                }
            }
        }
        return null;
    }

    /** 从玩家背包扣减 1 根火把（首次出现的火把槽位） */
    private static void consumeTorch(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack stack = p.getInventory().getStack(i);
            if (stack.isOf(Items.TORCH)) {
                stack.decrement(1);
                return;
            }
        }
    }

    /** 三维欧氏距离 */
    private static double dist3d(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
