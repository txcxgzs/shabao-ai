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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 【防篡改·运行时校验模拟器】不依赖 Minecraft，用与 GuardCore/ObfXor 完全相同的
 * AES-GCM（A2）与 Ed25519 算法对加密内测版 jar 做端到端验证：
 * <ol>
 *   <li>{@code pass <jar>}：读取 obf_manifest.dat → 公钥验签（keys/ed25519_public.pem）→
 *       解析（含 _expire 时间锁）→ 重算每个条目 SHA-256 比对，全部一致输出 PASS
 *       （等价 GuardCore.check() 通过路径）。</li>
 *   <li>{@code tamper <jar> <条目>}：把指定条目内容篡改（xor 一个字节）后重新打包，
 *       再执行 pass 应输出 FAIL —— 模拟"篡改后完整性校验失败、AI 锁定"。</li>
 * </ol>
 */
public final class VerifyIntegrity {

    private static final String MANIFEST_ENTRY = "obf_manifest.dat";
    private static final String EXPIRE_FIELD = "_expire";
    private static final String BUILD_FIELD = "_build";
    /** 与 BuildTools 一致的清单前缀：M2 + base64(json) + ";" + base64(sig) */
    private static final String MF_PREFIX = "M2";
    /** 验签公钥路径（与 GuardCore 内置公钥同源：X.509 PEM） */
    private static final String PUBLIC_KEY_FILE = "keys/ed25519_public.pem";

    /* ===== 与 ObfXor 完全一致的 AES-GCM 实现（A2：每字符串独立盐派生密钥） ===== */
    private static final String PREFIX = "A2";
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String S1 = "7qW3#eR9";
    private static final String S2 = "mK8vZ1@x";
    private static final String S3 = "s8Hk2@#zQ";

    private VerifyIntegrity() {}

    private static SecretKeySpec deriveKey(byte[] salt) throws Exception {
        String seed = "txcxgzs2026" + S1 + S2 + S3
                + new StringBuilder(S1 + S2 + S3).reverse().toString();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(seed.getBytes(StandardCharsets.UTF_8));
        md.update(salt);
        return new SecretKeySpec(md.digest(), "AES");
    }

    /** 解密（与 ObfXor.de 一致，A2） */
    private static String de(String cipher) throws Exception {
        byte[] all = Base64.getDecoder().decode(cipher.substring(PREFIX.length()));
        if (all.length <= SALT_LEN + IV_LEN) throw new IllegalStateException("密文过短");
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(all, 0, salt, 0, SALT_LEN);
        System.arraycopy(all, SALT_LEN, iv, 0, IV_LEN);
        byte[] ct = new byte[all.length - SALT_LEN - IV_LEN];
        System.arraycopy(all, SALT_LEN + IV_LEN, ct, 0, ct.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** 读 X.509 PEM 公钥 */
    private static PublicKey loadPublicKey() throws Exception {
        File f = new File(PUBLIC_KEY_FILE);
        if (!f.isFile()) throw new IllegalStateException("缺少公钥文件 " + PUBLIC_KEY_FILE);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("-----BEGIN") || t.startsWith("-----END")) continue;
                sb.append(t);
            }
        }
        byte[] der = Base64.getDecoder().decode(sb.toString());
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 模拟 GuardCore.loadManifest：验签 → 返回清单 {路径: hash}（不含 _expire/_build） */
    private static Map<String, String> loadManifest(JarFile jar, PublicKey pub) throws Exception {
        JarEntry e = jar.getJarEntry(MANIFEST_ENTRY);
        if (e == null) throw new IllegalStateException("jar 内缺少 " + MANIFEST_ENTRY);
        String content;
        try (InputStream in = jar.getInputStream(e)) {
            content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).trim();
        }
        if (!content.startsWith(MF_PREFIX)) {
            throw new IllegalStateException("清单格式非法（缺前缀 " + MF_PREFIX + "）");
        }
        String rest = content.substring(MF_PREFIX.length());
        int semi = rest.indexOf(';');
        if (semi < 0) throw new IllegalStateException("清单缺少签名段");
        byte[] jsonBytes = Base64.getDecoder().decode(rest.substring(0, semi));
        byte[] sigBytes = Base64.getDecoder().decode(rest.substring(semi + 1));
        // Ed25519 验签：签名无效视为篡改
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pub);
        verifier.update(jsonBytes);
        if (!verifier.verify(sigBytes)) {
            throw new IllegalStateException("清单签名无效（清单被篡改或公钥不匹配）");
        }
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        Map<String, String> out = new HashMap<>();
        String s = json.trim();
        if (!s.startsWith("{")) throw new IllegalStateException("清单不是 JSON");
        s = s.substring(1, s.length() - 1);
        for (String pair : s.split(",")) {
            int c = pair.indexOf(':');
            if (c <= 0) continue;
            out.put(unquote(pair.substring(0, c)), unquote(pair.substring(c + 1)));
        }
        return out;
    }

    private static void verify(String jarPath) throws Exception {
        int checked = 0;
        String expire = null;
        try (JarFile jar = new JarFile(jarPath)) {
            Map<String, String> expect = loadManifest(jar, loadPublicKey());
            if (expect.isEmpty()) throw new IllegalStateException("清单为空");
            for (Map.Entry<String, String> en : expect.entrySet()) {
                String k = en.getKey();
                if (k.equals(EXPIRE_FIELD)) { expire = en.getValue(); continue; }
                if (k.equals(BUILD_FIELD)) continue;
                JarEntry je = jar.getJarEntry(k);
                if (je == null) throw new IllegalStateException("条目缺失: " + k);
                byte[] data;
                try (InputStream in = jar.getInputStream(je)) {
                    data = in.readAllBytes();
                }
                String got = toHex(MessageDigest.getInstance("SHA-256").digest(data));
                if (!got.equalsIgnoreCase(en.getValue())) {
                    throw new IllegalStateException("条目被篡改: " + k
                            + " (expect=" + en.getValue() + ", got=" + got + ")");
                }
                checked++;
            }
        }
        if (expire == null) {
            throw new IllegalStateException("加密分发版授权日期缺失");
        }
        java.time.LocalDate deadline = java.time.LocalDate.parse(expire);
        System.out.println("  加密分发版授权至: " + expire);
        if (java.time.LocalDate.now().isAfter(deadline)) {
            throw new IllegalStateException("加密分发版授权已过期");
        }
        System.out.println("PASS 完整性校验通过（Ed25519 签名有效），共比对 " + checked + " 个条目（含资源）");
    }

    /** 篡改指定条目并重新打包到 .tampered.jar */
    private static void tamper(String jarPath, String entryPath, String outPath) throws Exception {
        byte[] flipped = null;
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry je = jar.getJarEntry(entryPath);
            if (je == null) throw new IllegalStateException("jar 内没有 " + entryPath);
            try (InputStream in = jar.getInputStream(je)) {
                flipped = in.readAllBytes();
            }
        }
        flipped[flipped.length / 2] ^= 0x5A; // 翻转中间字节，必然破坏内容
        try (JarFile jar = new JarFile(jarPath);
             JarOutputStream out = new JarOutputStream(new FileOutputStream(outPath))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                byte[] content;
                try (InputStream in = jar.getInputStream(e)) {
                    content = in.readAllBytes();
                }
                if (e.getName().equals(entryPath)) content = flipped;
                JarEntry copy = new JarEntry(e.getName());
                copy.setTime(e.getTime());
                out.putNextEntry(copy);
                out.write(content);
                out.closeEntry();
            }
        }
        System.out.println("已篡改 " + entryPath + " -> " + outPath);
    }

    /** 删除指定条目并重新打包，用于确认清单缺失时严格失败。 */
    private static void remove(String jarPath, String entryPath, String outPath) throws Exception {
        boolean found = false;
        try (JarFile jar = new JarFile(jarPath);
             JarOutputStream out = new JarOutputStream(new FileOutputStream(outPath))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().equals(entryPath)) {
                    found = true;
                    continue;
                }
                JarEntry copy = new JarEntry(e.getName());
                copy.setTime(e.getTime());
                out.putNextEntry(copy);
                try (InputStream in = jar.getInputStream(e)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        if (!found) throw new IllegalStateException("jar 内没有 " + entryPath);
        System.out.println("已删除 " + entryPath + " -> " + outPath);
    }

    private static void expectRejected(String out, String label) throws Exception {
        try {
            verify(out);
        } catch (Exception e) {
            System.out.println("PASS " + label + "被拒绝: " + e.getMessage());
            return;
        }
        throw new IllegalStateException(label + "后校验仍通过");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: VerifyIntegrity pass <jar> | tamper/remove <jar> <条目路径>");
            System.exit(1);
        }
        String jar = new File(args[1]).getAbsolutePath();
        switch (args[0]) {
            case "pass" -> verify(jar);
            case "tamper" -> {
                String out = jar.replace(".jar", ".tampered.jar");
                tamper(jar, args[2], out);
                System.out.println("—— 篡改后重新校验，预期 FAIL ——");
                expectRejected(out, "篡改");
            }
            case "remove" -> {
                String out = jar.replace(".jar", ".removed.jar");
                remove(jar, args[2], out);
                System.out.println("—— 删除条目后重新校验，预期 FAIL ——");
                expectRejected(out, "删除条目");
            }
            default -> { System.err.println("未知模式"); System.exit(1); }
        }
    }
}
