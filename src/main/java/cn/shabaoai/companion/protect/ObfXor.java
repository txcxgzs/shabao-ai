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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 【防篡改·加密工具】AES-GCM 对称加密 + 每字符串独立密钥派生（A2 版）。
 * <p>
 * 相对 A1 版的强化（A1 是"一把主密钥加密所有字符串"，破一处全开）：
 * <ul>
 *   <li><b>每字符串随机盐</b>：每次加密先生成 16 字节随机盐 salt，
 *       密钥 {@code K = SHA-256(masterSeed || salt)} —— 每个字符串/每次加密密钥都不同，
 *       同一明文加密两次结果也不同，频率/模式比对全部失效；</li>
 *   <li><b>随机 IV（nonce）</b>：AES-GCM 每次加密独立随机 IV，防止重放与模式关联；</li>
 *   <li><b>GCM 认证标签</b>：篡改密文/盐/IV 任一位都会解密失败，防逐字节 patch；</li>
 *   <li><b>版本前缀</b>：密文带 {@code A2} 前缀，密钥/算法轮换时改前缀即可整体切换。</li>
 * </ul>
 * 注意：masterSeed 与解密逻辑最终仍存在于客户端 JVM，本地字符串加密无法做到绝对不可逆，
 * 它提升的是静态分析/批量还原的成本。真正敏感的数据（API Key、授权主密钥）应放服务端。
 *
 * <p><b>算法一致性约束</b>：密钥派生与加解密逻辑必须和构建工具
 * {@code tools/BuildTools.java}、验证器 {@code tools/VerifyIntegrity.java} 完全一致。
 * 修改任一侧的盐/算法，所有已生成密文与清单都会失效，必须全部重新生成。
 */
public final class ObfXor {
    /** 密文版本前缀（A1→A2：改为每字符串独立盐派生密钥；改算法时换新前缀） */
    private static final String PREFIX = "A2";
    /** 每字符串随机盐长度（字节）：盐变化 → 派生密钥变化 */
    private static final int SALT_LEN = 16;
    /** GCM 推荐 IV 长度（字节） */
    private static final int IV_LEN = 12;
    /** GCM 认证标签长度（bit） */
    private static final int TAG_BITS = 128;

    /** 分散盐段（与 BuildTools/VerifyIntegrity 逐字节一致，改动需同步三处） */
    private static final String S1 = "7qW3#eR9";
    private static final String S2 = "mK8vZ1@x";
    private static final String S3 = "s8Hk2@#zQ";

    private ObfXor() {}

    /**
     * 派生本字符串专属的 AES-256 密钥：masterSeed + 三段盐 + 反转盐，再混入本串随机盐。
     * 不同字符串携带不同 salt → 密钥各不相同，还原单个密文拿不到其他字符串。
     */
    private static SecretKeySpec deriveKey(byte[] salt) throws Exception {
        String seed = "txcxgzs2026" + S1 + S2 + S3
                + new StringBuilder(S1 + S2 + S3).reverse().toString();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(seed.getBytes(StandardCharsets.UTF_8));
        md.update(salt);
        return new SecretKeySpec(md.digest(), "AES");
    }

    /** 加密：随机盐 + 随机 IV + AES-GCM，输出 {@code A2 + base64(salt||iv||密文)} */
    public static String en(String plain) {
        try {
            byte[] salt = new byte[SALT_LEN];
            new SecureRandom().nextBytes(salt);
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[SALT_LEN + IV_LEN + ct.length];
            System.arraycopy(salt, 0, all, 0, SALT_LEN);
            System.arraycopy(iv, 0, all, SALT_LEN, IV_LEN);
            System.arraycopy(ct, 0, all, SALT_LEN + IV_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(all);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    /** 解密：解析前缀 → 取盐派生本串密钥 → GCM 解密并校验认证标签（篡改/错密钥直接抛异常） */
    public static String de(String cipher) {
        try {
            if (cipher == null || !cipher.startsWith(PREFIX)) {
                throw new IllegalArgumentException("未知密文格式（缺前缀 " + PREFIX + "）");
            }
            byte[] all = Base64.getDecoder().decode(cipher.substring(PREFIX.length()));
            if (all.length <= SALT_LEN + IV_LEN) throw new IllegalArgumentException("密文过短");
            byte[] salt = Arrays.copyOfRange(all, 0, SALT_LEN);
            byte[] iv = Arrays.copyOfRange(all, SALT_LEN, SALT_LEN + IV_LEN);
            byte[] ct = Arrays.copyOfRange(all, SALT_LEN + IV_LEN, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 解密失败（密文被篡改或密钥不匹配）", e);
        }
    }
}
