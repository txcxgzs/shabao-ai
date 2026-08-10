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

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 事件总线：常驻 Agent Runtime 的"是否唤醒 LLM"触发器（线程安全）。
 *
 * <p><b>设计意图</b>：本地高频循环（跟随/巡逻/技能 tick）不产 LLM 调用，
 * 只有<b>事件</b>（危险 / 玩家受伤 / 卡住 / 社交空闲 / 目标完成）才考虑唤醒 Brain。
 * 事件以队列形式暂存，由 {@link AgentRuntime#tick} 按节拍消费，
 * 生产者（技能层、实体 AI、未来接入点）可来自任意线程，因此底层用
 * {@link ArrayBlockingQueue} 保证发布/消费的线程安全，并为事件风暴提供硬上限。
 */
public final class EventBus {
    private static final int MAX_PENDING_EVENTS = 2048;
    /** 事件类型：本地循环中值得惊动 LLM 的信号 */
    public enum Type {
        /** 玩家发消息（对话触发，与聊天转发解耦的占位入口） */
        PLAYER_MESSAGE,
        /** 检测到危险（怪物逼近/岩浆/高空等） */
        DANGER,
        /** 玩家受伤 */
        PLAYER_HURT,
        /** AI 卡住（寻路失败/被阻挡） */
        STALLED,
        /** 社交空闲（长时间无事可做，可主动找玩家搭话） */
        SOCIAL_IDLE,
        /** 目标完成 */
        GOAL_DONE,
        /** 等待结束（wait/wait_until 到期或条件满足后恢复 RUNNING） */
        WAIT_DONE,
        /** 需要重新规划（goal 长期无实质进展） */
        REPLAN_REQUIRED,
        /** 认知脉冲（ADAPTIVE/ACTIVE 模式的周期性自检：世界变化够大才唤醒） */
        COGNITION_PULSE
    }

    /** 事件载荷：类型 + 所属玩家 + 附加数据（文本描述，可为空） */
    public record Ev(Type type, UUID player, String data) {}

    /** 待消费事件队列（线程安全） */
    private final ArrayBlockingQueue<Ev> queue = new ArrayBlockingQueue<>(MAX_PENDING_EVENTS);

    /** 构造包内可见：仅允许同包的 AgentRuntime 创建（避免外部随意 new） */
    EventBus() {}

    /** 发布一个事件（任意线程可调，入队即可返回，不阻塞） */
    public void publish(Type type, UUID player, String data) {
        Ev event = new Ev(type, player, data);
        while (!queue.offer(event)) {
            queue.poll();
        }
    }

    /** 取出一条待消费事件（无事件返回 null） */
    public Ev poll() {
        return queue.poll();
    }

    /** 当前是否有待消费事件 */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void clear() {
        queue.clear();
    }
}
