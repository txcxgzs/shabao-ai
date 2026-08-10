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

package cn.shabaoai.companion.protect;

import cn.shabaoai.companion.ai.AgentLogger;
import cn.shabaoai.companion.build.BuildInfo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 【防篡改·完整性校验】内测分发保护核心（超级加密版）。
 * <p>
 * 多层防护：
 * <ol>
 *   <li><b>版权声明洪泛</b>：{@link #LICENSES} 存放 12 条 AES-GCM 加密的中英版权声明，
 *       全部标注<b>内测版</b>并写明沙包AI由 txcxgzs 开发；运行时解密打印到日志（不占游戏 UI）；
 *       每条解密后必须含作者标识，防篡改者替换版权文字。</li>
 *   <li><b>跨类版权哨兵</b>：5 个核心类各自内嵌一条 AES-GCM 加密哨兵（{@code licenseStamp()}），
 *       删除任一条哨兵 → 校验失败锁定 AI。</li>
 *   <li><b>反调试</b>：检测 JVM 调试参数（jdwp/agentlib/jvmti），内测版拒绝调试。</li>
 *   <li><b>类级 + 资源级完整性清单</b>：构建期把全部模组 class 与关键资源（fabric.mod.json、
 *       知识库 JSON、assets）的 SHA-256 组装 JSON 清单，注入 {@code _expire}（时间锁）与
 *       {@code _build}（构建号），用 Ed25519 <b>私钥</b>签名后写入 {@code obf_manifest.dat}；
 *       运行时用内置<b>公钥</b>验签 + 重算比对，任一条目被修改/删除/重新签名失败即置篡改标记。
 *       客户端只含公钥，私钥仅存构建侧——攻击者无法生成合法签名的新清单。</li>
 *   <li><b>内测版时间锁</b>：清单内 {@code _expire} 字段记录授权截止日期，过期即锁定；
 *       缺失/非法日期同样判定失败（防删除过期字段绕过）。</li>
 * </ol>
 * AI 工作入口（AgentExecutor）检测到篡改标记后拒绝执行并提示。
 *
 * <p><b>构建 Profile 门（P0）</b>：本类是否执行完整性校验由编译期常量
 * {@link BuildInfo#PROTECTED_BUILD} 决定，而非"jar 内有没有 obf_manifest.dat"：
 * <ul>
 *   <li>自用/未加密版（默认 self-use profile，由 build.gradle 生成 BuildInfo）：启动时
 *       直接把状态置为 {@code VERIFIED}，跳过反调试/版权/清单全部校验，AI 正常使用；</li>
 *   <li>受保护分发版（-PbuildProfile=protected 构建）：才执行下方全部校验，
 *       任一失败即置 {@code FAILED} 锁定 AI（fail-closed，删清单/改字节码都无法绕过）。</li>
 * </ul>
 * 该常量被 javac 内联进 {@link #check()}：自用版字节码里根本没有校验路径，
 * 保护版即使删除 BuildInfo 类也无法回到"跳过校验"（常量已在调用点内联）。
 *
 * <p>方法名采用短名（语义混淆），配合详细注释保证可审计。
 */
public final class GuardCore {
    /**
     * 完整性状态默认拒绝：只有完整校验全部成功后才允许 AI 工作。
     * 这能阻止“删清单”以及直接 NOP 掉启动校验调用造成的 fail-open。
     */
    private enum IntegrityState { UNVERIFIED, VERIFIED, FAILED }
    private static final java.util.concurrent.atomic.AtomicReference<IntegrityState> STATE =
            new java.util.concurrent.atomic.AtomicReference<>(IntegrityState.UNVERIFIED);

    /**
     * Ed25519 验签公钥（X.509 DER 的 Base64，由 tools/BuildTools.java 的 genkeys 生成并打印）。
     * 私钥只存在构建侧 keys/ed25519_private.pem（不入库），换钥需同步替换本常量并重新打包。
     */
    private static final String ED25519_PUBLIC =
            "MCowBQYDK2VwAyEAdRMSIFhqD58MIks6IH8IrKKnj4gjny8jP1/QjX6AeW0=";

    /** 清单文件格式前缀：M2 + base64(json) + ";" + base64(sig)（与 BuildTools 一致） */
    private static final String MF_PREFIX = "M2";
    /** 清单内的构建号字段名（签名元数据，校验时忽略） */
    private static final String BUILD_FIELD = "_build";

    /**
     * 12 条版权声明密文（AES-GCM，明文见各条注释；由 tools/BuildTools.java 用同一密钥生成，
     * 随机 IV 导致每次生成结果不同，改动密钥需全部重生成）。全部标注内测版并写明作者 txcxgzs。
     */
    private static final String[] LICENSES = {
            // 沙包AI（Shabao AI）由 txcxgzs 开发与维护，版权所有（C）2026 txcxgzs。此为内测版。
            "A21Xxf7NK+Zf0kCePtob6hq0BwTNb+BsnDiwWqvT9OgMIQAH/uYF9s1HbghIBkxAJdqN1LvJs0onJpVsb+OKf23AZ7Kx64lWhFDrM24s2CtG+eP37fdgEmOIG+r75oBRp0MdkEQAerxgpIca3oeqOIfDIIZjvsnx5wSI0j88I63GKxyKzEAS0O+b8K+5lxDsSb7qOsSCU=",
            // 本程序为 txcxgzs 发布的授权内测版，严禁未授权分发、二次打包、商用或去除版权信息。
            "A21JP74D0K0k534JWVoEZX6T+5xFw5aZ6hA9llzHoQoqkUHy0lp5YUCI6UFGhqDFFFFlZrz+vF76KV3zJo92WGL+J8YuX6bvCd0OubKe1WZHr1gVcI4dhspd299/Pr79VkmMP1fESSyWJu5CG8qSECtzrR4X2sa62cEY/LQtL197Iio4zZ/zHZ8dR9OVeDpdZCgFsDpiskDZi/WGA8PDtDPKs=",
            // Shabao AI is developed by txcxgzs. This is an internal test build; unauthorized redistribution or tampering is strictly prohibited.
            "A2PsQfRUQWoWtFcneWlVI0i6r2419WE5702C5iiiN3mLr2pFCqWSXnVo2l2h0HGbPVDEwO95B3VWsXF3r5B4/JlCx41umRf+wXXXZrHUjXPZbzZTLj8CsRrLdtI1Stm3oNo1ZSdICl0ZfrZ1PFuFf6XBZKcv0ToP1G37I4UdsSBYQLZbeNgTIbw0BRdoGsm2MSylU6enZjJlXEGMIK9DXEQmmyJCupHdxcJbUwl5qwhw==",
            // 沙包AI是 txcxgzs 的原创作品，此为内测版，任何个人或组织未经书面授权不得复制、修改、分发。
            "A2JPa5/EJx8x5pFf4cGFEWX+22fFjZc/kw6hMqCvHCWirea4Kl8ygVp4TW4CYy0cQ2u4QN7q50pWjxMV/77U2A+c10GvoNGtsCk9eKn4h8Y/M3GgYAGSNTX4oMM+xXIbfXXMquaonIqr3KwVAU1bHPTI7GN2Wgwi3YWK1oJ1GWn+cKu5fEGr6UAUqla6AW6854IFmQj+lUxT5RZxdpya9SBmElfWBVPm8F4kQVoQ==",
            // 本模组版权归 txcxgzs 所有，当前为内测版。发现程序被改动或版权信息被删除，请立即停止使用并向官方举报。
            "A2sZ/0w65oFMjWB0mTPGTt0PxkHapYDTtK7ultoU0uxH7Lew0iCuDP9P0bXCu+qmTCv4P8C9veoZ+acwgj+GRGNEVrVaCSZ2m79gnipi4NpZkj2drf/bWMIPUhVwF6QP9UDgZWqIl6q79luctbGLYuSMKtXYwf701I2ylitxL8jEqJlSrIbt0iX5QbACRK67n4VO+rm8vyQhlNqZ+/EjY+E421//STTlMQ1tNYpVrx0ShUD7dNGkVdrEZCt7wkqDE=",
            // Copyright (C) 2026 txcxgzs. All rights reserved. Shabao AI Companion (internal test build).
            "A2lBE82LlqCyO3r77XBkizDquoXduVQADrBPaUd3dmgqVMY0GITtsSjsAhz17BEzjVJul4F9hNuB8qbq3VE0icaZ/4X39gs4GXibHbwnYvHZYjg6FPbzpxc/qWl1s7VkMAktBlWXW6H4CCmyqJwHZAQhRdYwsDYhNMn1xG3+6+1dPxyjLZmK78",
            // 沙包AI（Shabao AI）项目由 txcxgzs 独立开发，此为内测版，代码、资源与设计均为原创。
            "A2G0pP2G/XFfg0E7JVYbNUzZo5BPsllKo463DsCMRCEmkoReuAk8XIT8aNSEuxikX1Y6RYVIe5q+lFKeC6xNbDITTmDtI8yOHoUaaPX1KVAI3XPzAF94DeKsogWmDZzACN+6Nwk0My03TpoHqpKMjPHUU2WoX9SZALWExa2FPRHnquFoaeA4hZQKguZOxMG4FRN63oDFqeNR1hjYkogg==",
            // 授权内测版，仅供体验，禁止任何形式的二次发布与商业用途。作者：txcxgzs。
            "A20Fgv4nyuSUolZggKATjsBZY6PfeDkEQQcklUrTGXr0LdAdHim8RACTnaA1TehtDfnRcvSlGeZmlEOBgXKww/r8lU2FjM7EjOZPDDo70mF+ptMbnVWx30Ech9/FuqJABe9jUjzpvdyX6uFdr25wlhVfhl+kknklR7SXFcdwAnoWzAPV2t/4WAitebO/XToGC2cTXD",
            // Shabao AI, an original creation by txcxgzs, is distributed as an authorized internal test build under a limited license.
            "A26HQXACwKIWnDuiZW8HKtpsHZubnnR7j3S83cPsI37q57JyPtx3KXbfvxAmG9RHR8rHhADC5pg87i8z9eCN5WNsHgDrduQTJTVl1LhI6wYV0ut9EzxZcaILIUeeo/afpsSCDD591Lw4Xh0Oq0oTkR9fnXYFxMMO/C7LMG0wosiRUMtI6MFzu3QAzP0MKCXe25rQAPqCqDx5Q4E2JV0EXElzIpWWg=",
            // 温馨提示：本内测版由 txcxgzs 制作，请尊重作者劳动成果，勿破解、勿改包、勿倒卖。
            "A2yIr0fbak/q/adJdaYuohRhetriJm4Ombw0LFTZrsTZSwL/rgTgpycjmfJzc1E35tdUfXsD1cco4B4a17eCaQA1KXi/P6SjAIvBCA9+8lrAP/dMhVaspLpOclbP8osfzARtz1G5tesrxnNo1XvGVZMULiaTmO51nIrgvdvPBRYPOmLsPQZM2Ryed0FgbZ6lPGvlQEdeFUd4rRB2JK5ko=",
            // This build is an authorized internal test release by txcxgzs. Any modification voids the license.
            "A2SfcZPqbIPim7MkkEpgvA8gAPr/w40qhBzkO0WN1JmqWglxGsjLaMnRkthhyi41JAb/+F6VwY70nV2YYAlff01fDsqKz1WF3gDh2a+5x5GloBavLIi6Etk2vd2irxhRtmrM4S/Hy0tmqeAVp/qbch4RoRVIvci68A23RdWw629S6Sa0jfG+sk4H4wqk7u",
            // 沙包AI（Shabao AI）版权声明：开发者 txcxgzs，此为内测版，任何篡改与未授权传播均属侵权。
            "A2K8/9FcFkKIwaxjo+Uj/rfAioOZacyZJw7iBfDrQ8f9JlpzZ+zG9WnepyqHsklAgQW82vKYK5W+fNchXGvrQXfDaGThDxbyEeQiNxggsOwmxZT8N+c9saJktmVgU77eY24naQsADTaqOo1pLJvQ66p3pMFiTbVuPsMtcQpa7ugrCnC5XaD/+ruqcSzVoOn1z21ROIP/+5yBymMFvuUPEmNhl+QsIT"
    };
    /** 篡改失败提示（AES-GCM 密文） */
    private static final String MSG_TAMPER =
            // 本程序为 txcxgzs 开发的沙包AI（Shabao AI）授权内测版，检测到程序被非法修改，AI 功能已锁定。请从官方渠道重新获取原版。
            "A223HMwcxpgyPGLwcKOVopy2LgfMbEBRGIYuy62lGGEOk9W8hegEtYa3MzTbdfu/UE/tr+N6mLfb0MR5s5pUDsy6VUnE8fuiTEYrYsclZv+cMdTDMjsiPKXZ1klk9ss4cV8yzgxQAyRvHXHXn7lXtOvKYxcMUUsArZznJqB8wfvOKqB+20iel37nMde63kqTsDocTID+yR7HLXqWoHn5aXQFGpPgZZqKPGjrPhwyBw9wwrTkcLvdH83cGINbmtP5+8I/VEQ0DmeB+PY5RIO+3WOA==";
    /** 内测版过期提示（AES-GCM 密文） */
    private static final String MSG_EXPIRED =
            // 内测版授权已过期，请联系 txcxgzs 获取最新版本。
            "A2APsR0t3wkWzxJDMPSUmjactS8TceUR3G4L7VzNjBn8uR6vzQSFRP1eXqqUfeNnVsXPbPptbb0vWw9xsUa/vJMC4e8EhSiIHu1eVtLGEQGfcCUrLKlksRQ0z7oNEO3xJ95XsRjvcF0FeWk9tvZ2w=";
    /** 清单内的内测版过期字段名 */
    private static final String EXPIRE_FIELD = "_expire";

    private GuardCore() {}

    /** 未验证与验证失败都拒绝工作；不能把“尚未执行校验”误当成安全。 */
    public static boolean isTampered() {
        return STATE.get() != IntegrityState.VERIFIED;
    }

    /** 启动时完整性校验入口：只执行一次，任何未完成或异常状态均保持拒绝。 */
    public static synchronized void check() {
        if (STATE.get() != IntegrityState.UNVERIFIED) return;
        // 【P0·构建 Profile 门】自用/未加密版：编译期常量 PROTECTED_BUILD=false，
        // 直接置 VERIFIED 并返回，跳过反调试/版权/清单全部校验——该类 jar 本就不带
        // obf_manifest.dat。注意：绝不能改用"清单是否存在"来判断（保护版删清单必须
        // fail-closed），"是否校验"必须由构建 profile 编译进字节码决定。
        if (!BuildInfo.PROTECTED_BUILD) {
            STATE.set(IntegrityState.VERIFIED);
            AgentLogger.logInfo("【防篡改】自用未加密版（profile=" + BuildInfo.BUILD_PROFILE
                    + "）：跳过完整性校验，AI 正常使用。");
            return;
        }
        try {
            verifyAll();
        } catch (Throwable t) {
            fail("完整性校验发生未处理异常: " + t);
        }
    }

    private static void verifyAll() {
        // 0) 反调试：内测版拒绝调试器附着（jdwp/agentlib/jvmti）
        if (hasDebugAgent()) {
            fail("检测到 JVM 调试参数，内测版禁止调试");
            return;
        }
        // 版权标记自检：作者标识密文必须能正确解密（防篡改者改密钥/密文）
        String author;
        try {
            author = ObfXor.de("A20f59ee0HGChTqdhxkP11oVeNPgkVZLZ76H56imZf+F5HC0L/qHjEU8sAXf8w8IELA2qh"); // "txcxgzs"
        } catch (Exception e) {
            fail("作者标识密文损坏");
            return;
        }
        // 1) 版权声明洪泛校验：12 条逐条解密核对作者标识
        for (int i = 0; i < LICENSES.length; i++) {
            if (!ObfXor.de(LICENSES[i]).contains(author)) {
                fail("版权声明条目 #" + (i + 1) + " 被篡改或删除");
                return;
            }
        }
        // 2) 跨类版权哨兵校验：5 个核心类内嵌哨兵，逐一解密核对作者标识
        String[] sentinels = {
                cn.shabaoai.companion.ai.AgentExecutor.licenseStamp(),
                cn.shabaoai.companion.ai.LlmClient.licenseStamp(),
                cn.shabaoai.companion.ai.AgentAction.licenseStamp(),
                cn.shabaoai.companion.ai.EnvironmentScanner.licenseStamp(),
                cn.shabaoai.companion.ai.BuildMemory.licenseStamp()
        };
        for (int i = 0; i < sentinels.length; i++) {
            if (!ObfXor.de(sentinels[i]).contains(author)) {
                fail("版权哨兵 #" + (i + 1) + " 缺失或被篡改（对应核心类被改动）");
                return;
            }
        }
        // 3) 类级 + 资源级完整性清单校验（obf_manifest.dat，Ed25519 验签）
        String manifest;
        try {
            manifest = loadManifest();
        } catch (ManifestCorruptException e) {
            // 清单存在但验签失败/格式非法：明确的篡改信号，必须锁定（不能降级）
            fail("完整性清单非法（签名无效或格式错误）: " + e.getMessage());
            return;
        }
        if (manifest == null || manifest.isBlank()) {
            // 公测/分发构建必须 fail-closed。普通未签名 JAR 不再拥有可运行 AI 的降级路径。
            fail("完整性清单 obf_manifest.dat 缺失");
            return;
        }
        java.util.Map<String, String> expect = parseManifest(manifest);
        expect.remove(BUILD_FIELD); // 构建号是签名元数据，不参与条目比对
        if (expect.isEmpty()) {
            fail("完整性清单解析结果为空");
            return;
        }
        // 4) 内测版时间锁：_expire 必须存在且未过期（缺失/非法/过期都失败）
        String expire = expect.remove(EXPIRE_FIELD);
        if (expire == null || expire.isBlank()) {
            fail("内测版授权日期缺失");
            return;
        }
        try {
            LocalDate deadline = LocalDate.parse(expire.trim());
            // “授权至某日”包含当天；次日才进入过期状态。
            if (LocalDate.now().isAfter(deadline)) {
                fail(ObfXor.de(MSG_EXPIRED));
                return;
            }
        } catch (Exception e) {
            fail("内测版授权日期非法: " + expire);
            return;
        }
        // 5) 逐条比对 hash（class + 资源）
        java.util.Map<String, byte[]> actual = hashEntries(expect.keySet());
        for (java.util.Map.Entry<String, String> e : expect.entrySet()) {
            byte[] got = actual.get(e.getKey());
            if (got == null) {
                fail("关键条目缺失: " + e.getKey());
                return;
            }
            if (!toHex(got).equalsIgnoreCase(e.getValue())) {
                fail("关键条目被篡改: " + e.getKey());
                return;
            }
        }
        // 所有密码学、日期和条目检查均成功后，才由默认拒绝切换为允许。
        if (!STATE.compareAndSet(IntegrityState.UNVERIFIED, IntegrityState.VERIFIED)) {
            fail("完整性状态提交冲突");
            return;
        }
        // 全部通过：打印版权声明（仅日志，不做游戏内 UI）
        AgentLogger.logInfo("完整性校验通过（" + expect.size() + " 个条目 + "
                + sentinels.length + " 个版权哨兵，授权至 " + expire + "）");
        for (String c : LICENSES) {
            AgentLogger.logInfo(ObfXor.de(c));
        }
        for (String s : sentinels) {
            AgentLogger.logInfo("[" + ObfXor.de(s) + "]");
        }
    }

    /** 检测 JVM 是否带调试/代理参数（反调试，防动态调试绕过） */
    private static boolean hasDebugAgent() {
        try {
            var args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String a : args) {
                String t = a.toLowerCase();
                if (t.contains("jdwp") || t.contains("-agentlib:") || t.contains("jvmti")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 拿不到 JVM 参数时放行（不因检测失败误锁）
        }
        return false;
    }

    /** 校验失败处理：不可逆地进入 FAILED + 错误日志 + 版权警告。 */
    private static void fail(String why) {
        STATE.set(IntegrityState.FAILED);
        AgentLogger.logError(0, "【防篡改】程序完整性校验失败：" + why);
        try {
            AgentLogger.logError(0, ObfXor.de(MSG_TAMPER));
        } catch (Exception ignored) {
            AgentLogger.logError(0, "本程序为 txcxgzs 开发的沙包AI（Shabao AI）授权内测版，检测到程序被非法修改，AI 功能已锁定。");
        }
    }

    /** 清单损坏异常：验签失败或格式非法。 */
    private static final class ManifestCorruptException extends Exception {
        ManifestCorruptException(String msg) { super(msg); }
    }

    /**
     * 读取并校验 jar 内签名清单（obf_manifest.dat，格式 {@code M2 + base64(json) + ";" + base64(sig)}）。
     * @return 验签通过后的清单 JSON 明文；无清单时返回 null（调用方必须失败关闭）
     * @throws ManifestCorruptException 清单存在但验签失败或格式非法
     */
    private static String loadManifest() throws ManifestCorruptException {
        try (InputStream in = GuardCore.class.getResourceAsStream("/obf_manifest.dat")) {
            if (in == null) return null;
            String content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).trim();
            if (!content.startsWith(MF_PREFIX)) {
                throw new ManifestCorruptException("缺前缀 " + MF_PREFIX);
            }
            String rest = content.substring(MF_PREFIX.length());
            int semi = rest.indexOf(';');
            if (semi < 0) throw new ManifestCorruptException("缺少签名段");
            byte[] jsonBytes = java.util.Base64.getDecoder().decode(rest.substring(0, semi));
            byte[] sigBytes = java.util.Base64.getDecoder().decode(rest.substring(semi + 1));
            // Ed25519 验签：客户端只内置公钥，私钥在构建侧——伪造清单必须同时改公钥+verify()
            java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
            verifier.initVerify(ed25519PublicKey());
            verifier.update(jsonBytes);
            if (!verifier.verify(sigBytes)) {
                throw new ManifestCorruptException("签名验证失败（清单被篡改或公钥不匹配）");
            }
            return new String(jsonBytes, StandardCharsets.UTF_8);
        } catch (ManifestCorruptException e) {
            throw e;
        } catch (Exception e) {
            throw new ManifestCorruptException(e.toString());
        }
    }

    /** 用内置公钥常量构建 Ed25519 验签公钥（X.509 DER） */
    private static java.security.PublicKey ed25519PublicKey() throws Exception {
        byte[] der = java.util.Base64.getDecoder().decode(ED25519_PUBLIC);
        return java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
    }

    /**
     * 解析清单 JSON：{".class/资源路径": "sha256hex"}，含特殊字段 {@code _expire}。
     * 手写轻量解析（清单格式固定，无嵌套对象）。
     */
    private static java.util.Map<String, String> parseManifest(String json) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String s = json.trim();
        if (!s.startsWith("{")) return out;
        s = s.substring(1, s.length() - 1); // 去掉外层 {}
        for (String pair : s.split(",")) {
            int c = pair.indexOf(':');
            if (c <= 0) continue;
            String k = pair.substring(0, c).trim();
            String v = pair.substring(c + 1).trim();
            out.put(unquote(k), unquote(v));
        }
        return out;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 定位自身 jar，批量计算清单条目的 SHA-256（class 与资源通用） */
    private static java.util.Map<String, byte[]> hashEntries(java.util.Set<String> names) {
        java.util.Map<String, byte[]> out = new java.util.HashMap<>();
        try {
            java.net.URL url = GuardCore.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return out;
            String path = url.toURI().getPath();
            if (!path.toLowerCase().endsWith(".jar")) {
                // 开发环境目录模式：按文件读
                for (String n : names) {
                    java.io.File f = new java.io.File(path, n);
                    if (f.isFile()) out.put(n, sha256(f));
                }
                return out;
            }
            try (JarFile jar = new JarFile(path)) {
                for (String n : names) {
                    JarEntry entry = jar.getJarEntry(n);
                    if (entry == null) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        out.put(n, sha256(in.readAllBytes()));
                    }
                }
            }
        } catch (Exception e) {
            AgentLogger.logError(0, "计算清单哈希失败: " + e);
        }
        return out;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] sha256(java.io.File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
