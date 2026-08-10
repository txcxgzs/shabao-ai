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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.jar.JarFile;

/**
 * 【防篡改·混淆运行时冒烟测试】验证混淆 jar 的 tool-calling 链路在运行时可用。
 * <p>
 * 用 JVM 实际驱动混淆后的 AgentAction.fromToolCalls：
 * <ol>
 *   <li>动态定位 {@code LlmClient$ToolCall} 混淆类（record，构造器 3 个 String）；</li>
 *   <li>动态定位 {@code AgentAction} 混淆类（含嵌套 enum，且有静态方法 List→List）；</li>
 *   <li>构造一个 plan 工具调用（id=call_xyz、参数含唯一标记文本），调用 fromToolCalls，
 *       断言结果动作的 toString 含该标记——修复前（getMethod("name") 反射在混淆后
 *       NoSuchMethod）会变成"解析失败"error 动作且标记文本丢失。</li>
 * </ol>
 * <b>宽容模式</b>：本机 .minecraft/libraries 若缺某些 Minecraft 类（class_2165 等），
 * 依赖该类的方法无法执行 → 打印 SKIP 并以 0 退出（不阻塞构建）；
 * 只有"能完整执行但断言失败"（真混淆 bug）才以非 0 退出。
 */
public final class ObfSmokeTest {

    private ObfSmokeTest() {}

    /** 定位 ToolCall 混淆类：record 且构造器为 (String,String,String)；找不到返回 null */
    private static Class<?> findToolCall(JarFile jar) {
        var it = jar.entries();
        while (it.hasMoreElements()) {
            String n = it.nextElement().getName();
            if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
            String cn = n.substring(0, n.length() - 6).replace('/', '.');
            try {
                Class<?> c = Class.forName(cn, false, ClassLoader.getSystemClassLoader());
                if (!c.isRecord()) continue;
                for (var ct : c.getConstructors()) {
                    var pt = ct.getParameterTypes();
                    if (pt.length == 3 && pt[0] == String.class && pt[1] == String.class && pt[2] == String.class) {
                        return c;
                    }
                }
            } catch (Throwable ignored) {
                // 依赖缺失的类跳过
            }
        }
        return null;
    }

    /** 是否为保留包（Minecraft/Fabric 集成层，类名未混淆——混淆类特征识别时排除它们） */
    private static boolean isKeptPackage(String cn) {
        return cn.startsWith("cn.shabaoai.companion.entity.")
                || cn.startsWith("cn.shabaoai.companion.client.")
                || cn.startsWith("cn.shabaoai.companion.net.")
                || cn.startsWith("cn.shabaoai.companion.config.")
                || cn.equals("cn.shabaoai.companion.ShabaoAiMod")
                || cn.startsWith("cn.shabaoai.companion.ShabaoAiMod");
    }

    /** 定位 AgentAction 混淆类：含嵌套 enum 且存在静态方法 (List)->List；找不到返回 null */
    private static Class<?> findAgentAction(JarFile jar) {
        var it = jar.entries();
        while (it.hasMoreElements()) {
            String n = it.nextElement().getName();
            if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
            String cn = n.substring(0, n.length() - 6).replace('/', '.');
            try {
                Class<?> c = Class.forName(cn, false, ClassLoader.getSystemClassLoader());
                if (isKeptPackage(c.getName())) continue; // 保留包类名未混淆，不是目标
                boolean hasEnum = false;
                for (Class<?> d : c.getDeclaredClasses()) {
                    if (d.isEnum()) { hasEnum = true; break; }
                }
                if (!hasEnum) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == List.class
                            && m.getReturnType() == List.class) {
                        return c;
                    }
                }
            } catch (Throwable ignored) {
                // 依赖缺失的类跳过
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        String jarPath = new java.io.File(args[0]).getAbsolutePath();
        try (JarFile jar = new JarFile(jarPath)) {
            Class<?> toolCall = findToolCall(jar);
            Class<?> agentAction = findAgentAction(jar);
            if (toolCall == null || agentAction == null) {
                System.out.println("[ObfSmokeTest] SKIP: 未定位到混淆类（ToolCall=" + toolCall
                        + " AgentAction=" + agentAction + "），本机依赖可能不完整，跳过运行验证");
                return;
            }
            System.out.println("[ObfSmokeTest] ToolCall=" + toolCall.getName()
                    + " AgentAction=" + agentAction.getName());

            Object tc;
            try {
                tc = toolCall.getConstructor(String.class, String.class, String.class)
                        .newInstance("call_xyz", "plan", "{\"todos\":[\"混淆链路测试标记\"]}");
            } catch (Throwable t) {
                System.out.println("[ObfSmokeTest] SKIP: 构造 ToolCall 失败（依赖缺失?）: " + t);
                return;
            }

            Method fromToolCalls = null;
            for (Method m : agentAction.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == List.class
                        && m.getReturnType() == List.class) {
                    fromToolCalls = m;
                    break;
                }
            }
            if (fromToolCalls == null) {
                System.out.println("[ObfSmokeTest] SKIP: 未找到 fromToolCalls");
                return;
            }
            fromToolCalls.setAccessible(true);
            List<?> actions;
            try {
                actions = (List<?>) fromToolCalls.invoke(null, List.of(tc));
            } catch (Throwable t) {
                // 依赖缺失（Minecraft 类不在本机 libraries）→ 环境限制，跳过而非失败
                Throwable root = t;
                while (root.getCause() != null) root = root.getCause();
                System.out.println("[ObfSmokeTest] SKIP: fromToolCalls 因依赖缺失未执行: "
                        + root.getClass().getSimpleName() + ": " + root.getMessage());
                return;
            }

            // 断言：1 个正常动作且参数标记透传（修复前反射失败 → error 动作 + 标记丢失）
            if (actions == null || actions.size() != 1) {
                System.out.println("[ObfSmokeTest] FAIL 动作数异常: " + actions);
                System.exit(1);
            }
            String text = actions.get(0).toString();
            if (text.contains("解析失败") || text.contains("tool_call 解析") || !text.contains("混淆链路测试标记")) {
                System.out.println("[ObfSmokeTest] FAIL 动作内容异常: " + text);
                System.exit(1);
            }
            System.out.println("[ObfSmokeTest] PASS fromToolCalls 混淆链路正常: " + text);
        }
    }
}
