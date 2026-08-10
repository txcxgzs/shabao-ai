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

package cn.shabaoai.companion.ai;

/**
 * 认知策略（CognitionPolicy）：决定「这个 Goal 的大脑中根什么时候再想」。
 *
 * <p><b>设计意图</b>：本地 Skill 只解决"动作怎么持续"（确定性重复行为），
 * 但开放式任务（陪挖矿/自由陪伴/持续观察）需要<b>持续认知</b>——隔一段时间
 * 重新看世界、重新理解玩家、重新决定下一步。CognitionPolicy 把"什么时候想"
 * 从 Prompt 的"你自己判断"升级为 Runtime 强约束：
 *
 * <pre>
 *   LOCAL    基本不主动调用 LLM（纯跟随/自动火把——没必要问"还要不要跟"）
 *   EVENT    只有事件才调用（建筑执行/等待——事件由 EventBus 驱动）
 *   ADAPTIVE 事件 + 约 5~30 秒自检（陪挖矿/探索——平常低频，重大变化立即醒）
 *   ACTIVE   约 2~15 秒持续认知（自由陪伴/一起冒险——需要长时间反复理解世界）
 * </pre>
 *
 * <p><b>Runtime 校正（不能完全相信 LLM）</b>：LLM 建议的 mode/interval 经过
 * {@link #configure} 的强制区间 clamp；开放式 Goal 即使 LLM 建议 LOCAL 也会被
 * 上层（GoalManager.applyCognition）最低提升到 ADAPTIVE。LLM 还可以预约
 * "下次醒来"（{@link #scheduleNext}），Runtime 同样按 mode clamp。
 *
 * <p>线程安全：由服务端主线程读写（goal 状态机在 tick 主线程驱动），无需同步。
 */
public final class CognitionPolicy {
    /** 认知模式：LLM 调用频率从低到高 */
    public enum Mode {
        /** 基本不主动调用 LLM（纯跟随/自动火把） */
        LOCAL,
        /** 只有事件才调用 LLM（建筑执行/等待） */
        EVENT,
        /** 事件 + 约 5~30 秒自检（陪挖矿/探索） */
        ADAPTIVE,
        /** 约 2~15 秒持续认知（自由陪伴/一起冒险） */
        ACTIVE
    }

    /** ADAPTIVE 最短/最长自检间隔（ms）：最短 8s——低频自检绝不能烧爆 API */
    private static final long ADAPTIVE_MIN = 8_000, ADAPTIVE_MAX = 30_000;
    /** ACTIVE 最短/最长自检间隔（ms）：最短 4s——持续认知也留呼吸间隔 */
    private static final long ACTIVE_MIN = 4_000, ACTIVE_MAX = 15_000;
    /** 预约下次思考的 clamp：不得短于 4 秒（LLM 想"每 2 秒看一次"也不行，Runtime 说了算） */
    private static final long NEXT_THINK_MIN_MS = 4_000;

    /** 当前认知模式 */
    private volatile Mode mode = Mode.EVENT;
    /** 两次 Brain 思考的最小间隔（ms）；LOCAL/EVENT 为 0（不主动） */
    private volatile long minIntervalMs;
    /** 无变化时的自检硬上限（ms）：到点必须唤醒 Brain 兜底；0=无上限 */
    private volatile long maxIntervalMs;
    /** 上次 Brain 思考时间（epoch ms） */
    private volatile long lastThinkAt;
    /** 下次允许 Brain pulse 的时间（epoch ms） */
    private volatile long nextThinkAt;

    /** 构造包内可见：由 {@link #forType} 创建 */
    private CognitionPolicy() {}

    /**
     * 按目标类型给默认认知策略（每个 Goal 必须拥有 cognition policy，不能为空）：
     * follow / build 等机械型 → EVENT（只有事件才想）；
     * companion（自由陪伴）→ ACTIVE（持续认知，一起逛世界自己决定）；
     * 其余（mine_assist/explore/自定义）→ ADAPTIVE（低频自检 + 重大变化立即醒）。
     */
    public static CognitionPolicy forType(String goalType) {
        CognitionPolicy p = new CognitionPolicy();
        if ("follow".equals(goalType) || "build".equals(goalType)) {
            p.configure(Mode.EVENT, 0, 0, -1);
        } else if ("companion".equals(goalType)) {
            p.configure(Mode.ACTIVE, 0, 0, -1); // 自由陪伴：持续认知窗口
        } else {
            p.configure(Mode.ADAPTIVE, 0, 0, -1); // 用默认自适应区间
        }
        return p;
    }

    /**
     * 应用认知策略（Runtime Policy Validator 入口）：LLM 建议经强制区间 clamp，
     * 绝不出现"LOCAL 每 5 秒烧一次"或"ACTIVE 永不调用"。
     *
     * @param minSec 最短间隔（秒，≤0 用默认）；maxSec 最长间隔（秒，≤0 用默认）
     * @param nextAfterSec 预约下次思考的秒数（>0 生效，按 mode clamp）
     */
    public void configure(Mode mode, long minSec, long maxSec, long nextAfterSec) {
        this.mode = mode == null ? Mode.EVENT : mode;
        switch (this.mode) {
            case ADAPTIVE -> {
                // 【r9-频率硬限制】模型传的 min_interval 只作"想更勤"的参考，向下 clamp 到
                // ADAPTIVE_MIN（8s）："adaptive 最快约 8 秒"是硬承诺，模型填 1 秒也无效
                this.minIntervalMs = Math.max(secToMs(minSec, ADAPTIVE_MIN), ADAPTIVE_MIN);
                this.maxIntervalMs = secToMs(maxSec, ADAPTIVE_MAX);
                this.maxIntervalMs = Math.max(this.maxIntervalMs, this.minIntervalMs);
            }
            case ACTIVE -> {
                this.minIntervalMs = Math.max(secToMs(minSec, ACTIVE_MIN), ACTIVE_MIN); // 硬下限 4s
                this.maxIntervalMs = secToMs(maxSec, ACTIVE_MAX);
                this.maxIntervalMs = Math.max(this.maxIntervalMs, this.minIntervalMs);
            }
            case LOCAL, EVENT -> {
                // 纯事件驱动：不主动 pulse（interval 无意义）
                this.minIntervalMs = 0;
                this.maxIntervalMs = 0;
            }
        }
        if (nextAfterSec > 0) {
            scheduleNext(nextAfterSec);
        } else {
            long now = System.currentTimeMillis();
            // 未显式预约：以当前为思考基准，下次 pulse 在 min_interval 之后（首次自检）
            if (this.lastThinkAt == 0) this.lastThinkAt = now;
            this.nextThinkAt = now + Math.max(minIntervalMs, NEXT_THINK_MIN_MS);
        }
    }

    /** 是否允许主动 Brain pulse（LOCAL/EVENT 不允许） */
    public boolean mayPulse() {
        return mode == Mode.ADAPTIVE || mode == Mode.ACTIVE;
    }

    /** 当前认知模式 */
    public Mode mode() {
        return mode;
    }

    /** 距离上次思考的最小间隔（ms） */
    public long minIntervalMs() {
        return minIntervalMs;
    }

    /** 是否已到下次 pulse 检查时间 */
    public boolean due(long now) {
        return nextThinkAt > 0 && now >= nextThinkAt;
    }

    /** 硬上限：到点必须唤醒 Brain（maxIntervalMs==0 表示无上限） */
    public long hardMaxAt() {
        return maxIntervalMs <= 0 ? Long.MAX_VALUE : lastThinkAt + maxIntervalMs;
    }

    /** 记录一次 Brain 思考：重置节奏，下次 pulse 在 minInterval 之后 */
    public void markThought(long now) {
        this.lastThinkAt = now;
        this.nextThinkAt = now + Math.max(minIntervalMs, NEXT_THINK_MIN_MS);
    }

    /** 变化不足时的延后：推到 minInterval 后再看（不超硬上限） */
    public void defer(long now) {
        this.nextThinkAt = Math.min(now + Math.max(minIntervalMs, NEXT_THINK_MIN_MS), hardMaxAt());
    }

    /** LLM 预约"下次醒来"：按 mode clamp 最短 2 秒、最长不超 maxInterval 的 1.5 倍 */
    public void scheduleNext(long afterSec) {
        if (!mayPulse()) return; // LOCAL/EVENT 不允许主动预约
        long afterMs = Math.max(afterSec * 1000L, NEXT_THINK_MIN_MS);
        long cap = maxIntervalMs <= 0 ? Long.MAX_VALUE : (long) (maxIntervalMs * 1.5);
        this.nextThinkAt = System.currentTimeMillis() + Math.min(afterMs, cap);
    }

    /** 秒转毫秒；<=0 用默认值 */
    private static long secToMs(long sec, long defaultMs) {
        return sec > 0 ? sec * 1000L : defaultMs;
    }
}
