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
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 【防篡改·构建期工具】独立于模组编译，仅依赖 JDK。
 *
 * <p>四个功能：
 * <ol>
 *   <li>{@code enc <明文>}：打印一条 AES-GCM 密文（A2：每字符串独立盐派生密钥）——供开发时
 *       生成版权声明/哨兵的加密文本，然后把密文贴进 GuardCore.java / 各核心类。</li>
 *   <li>{@code genkeys}：生成 Ed25519 签名密钥对（不存在时）。私钥写 {@code keys/ed25519_private.pem}
 *       （PKCS8 PEM，仅本机构建用，务必入库外），公钥写 {@code keys/ed25519_public.pem}
 *       （X.509 PEM），并打印公钥的 Base64 —— 运行时 GuardCore 内置该公钥验签。</li>
 *   <li>{@code manifest <输入jar> <输出jar> [过期日期=YYYY-MM-DD]}：扫描 jar 内模组 class 与
 *       关键资源（fabric.mod.json / 知识库 JSON / assets），逐个计算 SHA-256 组装 JSON 清单，
 *       注入 {@code _expire}（内测版时间锁）与 {@code _build}（构建号），再用私钥做 Ed25519 签名，
 *       以 {@code M2 + base64(json) + ";" + base64(sig)} 写入 {@code obf_manifest.dat} 注入输出 jar。</li>
 *   <li>{@code verify <jar>}：模拟运行时校验（公钥验签 + 哈希比对 + 过期检查，供开发期自检）。</li>
 * </ol>
 *
 * <p><b>算法一致性约束</b>：
 * <ul>
 *   <li>AES-GCM 密钥派生与加解密必须和 {@code cn.shabaoai.companion.protect.ObfXor} 完全一致；</li>
 *   <li>Ed25519 签名格式与 {@code GuardCore} 的验签逻辑完全一致。</li>
 * </ul>
 * 若修改任一侧的算法，所有已生成密文/清单都会失效，必须全部重新生成。
 */
public final class BuildTools {

    /** 需要校验的关键资源（防篡改资源文件） */
    private static final String[] KEY_RESOURCES = {
            "fabric.mod.json",
            "block_knowledge.json",
            "building_knowledge.json"
    };
    /** 资源目录前缀（lang 等） */
    private static final String ASSETS_PREFIX = "assets/";
    /** 清单文件名（GuardCore.loadManifest 读取同一个名字） */
    private static final String MANIFEST_ENTRY = "obf_manifest.dat";
    /** 内测版过期字段名（GuardCore 解析时特殊处理） */
    private static final String EXPIRE_FIELD = "_expire";
    /** 构建号字段名（GuardCore 解析时忽略） */
    private static final String BUILD_FIELD = "_build";
    /** 清单文件格式前缀：M2 = 明文 JSON + Ed25519 签名（替换旧 A1 的 AES 整包加密） */
    private static final String MF_PREFIX = "M2";
    /** Ed25519 私钥路径（PKCS8 PEM，仅构建期；公钥内嵌 GuardCore + keys/ed25519_public.pem） */
    private static final String PRIVATE_KEY_FILE = "keys/ed25519_private.pem";
    /** Ed25519 公钥路径（X.509 PEM，供 verify 自检与换钥核对） */
    private static final String PUBLIC_KEY_FILE = "keys/ed25519_public.pem";

    /* ===== 与 ObfXor 完全一致的 AES-GCM 实现（A2：每字符串独立盐派生密钥） ===== */
    private static final String PREFIX = "A2";
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String S1 = "7qW3#eR9";
    private static final String S2 = "mK8vZ1@x";
    private static final String S3 = "s8Hk2@#zQ";

    private BuildTools() {}

    private static SecretKeySpec deriveKey(byte[] salt) throws Exception {
        String seed = "txcxgzs2026" + S1 + S2 + S3
                + new StringBuilder(S1 + S2 + S3).reverse().toString();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(seed.getBytes(StandardCharsets.UTF_8));
        md.update(salt);
        return new SecretKeySpec(md.digest(), "AES");
    }

    /** 加密（与 ObfXor.en 一致，A2） */
    private static String en(String plain) throws Exception {
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
    }

    /** 解密（与 ObfXor.de 一致，A2，供 verify 自检） */
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

    /* ===== Ed25519 签名密钥管理 ===== */

    /**
     * 生成 Ed25519 密钥对（幂等：私钥已存在则跳过并提示）。
     * 私钥仅存本机构建目录（keys/，已 gitignore），公钥打印 Base64 供内嵌 GuardCore。
     */
    private static void genKeys() throws Exception {
        File priv = new File(PRIVATE_KEY_FILE);
        if (priv.isFile()) {
            System.out.println("[BuildTools] 私钥已存在: " + PRIVATE_KEY_FILE + "（复用，跳过生成）");
            printPublicB64();
            return;
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        File dir = priv.getParentFile();
        if (dir != null) dir.mkdirs();
        // PKCS8 PEM 私钥
        writePem(priv, "PRIVATE KEY", kp.getPrivate().getEncoded());
        // X.509 PEM 公钥（verify 自检用）
        writePem(new File(PUBLIC_KEY_FILE), "PUBLIC KEY", kp.getPublic().getEncoded());
        System.out.println("[BuildTools] 已生成 Ed25519 密钥对（私钥不入库）: "
                + PRIVATE_KEY_FILE + " / " + PUBLIC_KEY_FILE);
        printPublicB64();
    }

    /** 打印公钥 Base64（X.509 编码，GuardCore 内嵌用） */
    private static void printPublicB64() throws Exception {
        PublicKey pub = loadPublicKey();
        System.out.println("[BuildTools] 公钥(X.509 Base64，请填入 GuardCore.ED25519_PUBLIC)="
                + Base64.getEncoder().encodeToString(pub.getEncoded()));
    }

    private static void writePem(File f, String label, byte[] der) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 读 PKCS8 PEM 私钥 */
    private static PrivateKey loadPrivateKey() throws Exception {
        File f = new File(PRIVATE_KEY_FILE);
        if (!f.isFile()) {
            throw new IllegalStateException("缺少签名私钥 " + PRIVATE_KEY_FILE
                    + "，请先执行 BuildTools genkeys");
        }
        byte[] der = readPem(f, "PRIVATE KEY");
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** 读 X.509 PEM 公钥 */
    private static PublicKey loadPublicKey() throws Exception {
        File f = new File(PUBLIC_KEY_FILE);
        if (!f.isFile()) {
            throw new IllegalStateException("缺少公钥文件 " + PUBLIC_KEY_FILE);
        }
        byte[] der = readPem(f, "PUBLIC KEY");
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] readPem(File f, String label) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("-----BEGIN") || t.startsWith("-----END")) continue;
                sb.append(t);
            }
        }
        return Base64.getDecoder().decode(sb.toString());
    }

    /* ===== 清单生成（Ed25519 签名） ===== */

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /**
     * 判断 entry 是否纳入完整性校验。
     * 模组 jar 内只有我们自己的 class（Minecraft/Fabric 是外部依赖），
     * 因此直接校验 jar 内全部 class——ProGuard 打乱包名/类名后依然全覆盖。
     */
    private static boolean shouldHash(String name) {
        if (name.equals(MANIFEST_ENTRY)) return false; // 清单自身排除（自举）
        if (name.startsWith("META-INF/")) return false; // 签名/元数据不校验
        if (name.endsWith(".class")) return true;
        for (String r : KEY_RESOURCES) {
            if (name.equals(r)) return true;
        }
        if (name.startsWith(ASSETS_PREFIX) && !name.endsWith("/")) return true;
        return false;
    }

    /**
     * 生成内测版 jar：原 jar 的所有 entry 原样复制，并新增/覆盖 Ed25519 签名的完整性清单。
     * @param expireDate 内测版过期日期（YYYY-MM-DD），null 表示不启用时间锁
     */
    private static void buildManifest(String inJar, String outJar, String expireDate) throws Exception {
        File in = new File(inJar);
        if (!in.isFile()) {
            throw new IllegalStateException("输入 jar 不存在: " + inJar);
        }
        PrivateKey priv = loadPrivateKey(); // 私钥缺失直接报错，防止生成无法验签的包
        List<String> names = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        try (JarFile jar = new JarFile(in)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!shouldHash(name)) continue;
                byte[] data;
                try (InputStream is = jar.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                names.add(name);
                hashes.add(toHex(MessageDigest.getInstance("SHA-256").digest(data)));
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("jar 内没有可校验的模组类/资源");
        }
        // 组装清单 JSON 明文：_expire/_build 放首位（GuardCore 特殊处理这两个字段）
        StringBuilder json = new StringBuilder("{");
        if (expireDate != null && !expireDate.isBlank()) {
            json.append('"').append(EXPIRE_FIELD).append("\":\"").append(expireDate).append('"');
        }
        json.append(",\"").append(BUILD_FIELD).append("\":\"")
                .append(String.valueOf(System.currentTimeMillis())).append('"');
        for (int i = 0; i < names.size(); i++) {
            json.append(',');
            json.append('"').append(escapeJson(names.get(i))).append("\":\"")
                    .append(hashes.get(i)).append('"');
        }
        json.append("}");
        String jsonStr = json.toString();
        // Ed25519 签名（对明文 JSON 字节签名；GuardCore 用内嵌公钥验签）
        byte[] jsonBytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(priv);
        sig.update(jsonBytes);
        byte[] sigBytes = sig.sign();
        // 清单文件格式：M2 + base64(json) + ";" + base64(sig)（纯 ASCII，与 ISO_8859_1 读取兼容）
        String manifest = MF_PREFIX
                + Base64.getEncoder().encodeToString(jsonBytes)
                + ";" + Base64.getEncoder().encodeToString(sigBytes);

        // 复制原 jar 并注入清单
        try (JarFile jar = new JarFile(in);
             JarOutputStream out = new JarOutputStream(new FileOutputStream(outJar))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                JarEntry copy = new JarEntry(e.getName());
                copy.setTime(e.getTime());
                out.putNextEntry(copy);
                try (InputStream is = jar.getInputStream(e)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }
            JarEntry me = new JarEntry(MANIFEST_ENTRY);
            me.setTime(System.currentTimeMillis());
            out.putNextEntry(me);
            out.write(manifest.getBytes(StandardCharsets.ISO_8859_1));
            out.closeEntry();
        }
        System.out.println("[BuildTools] 已生成加密分发版: " + outJar);
        System.out.println("[BuildTools] 清单条目: " + names.size()
                + " (过期" + (expireDate == null ? "不启用" : expireDate) + ", Ed25519 签名)");
    }

    /** JSON 字符串转义（清单里只有路径和 hex，最小转义足够） */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 模拟运行时校验：验签 → 解析清单 → 重算 hash 比对 + 过期检查（开发期自检） */
    private static void verifyJar(String jarPath) throws Exception {
        PublicKey pub = loadPublicKey();
        int checked = 0;
        String expire = null;
        String build = null;
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry me = jar.getJarEntry(MANIFEST_ENTRY);
            if (me == null) throw new IllegalStateException("jar 内缺少 obf_manifest.dat");
            String content;
            try (InputStream is = jar.getInputStream(me)) {
                content = new String(is.readAllBytes(), StandardCharsets.ISO_8859_1).trim();
            }
            // 解析 M2 + base64(json) + ";" + base64(sig)
            if (!content.startsWith(MF_PREFIX)) {
                throw new IllegalStateException("清单格式非法（缺前缀 " + MF_PREFIX + "）");
            }
            String rest = content.substring(MF_PREFIX.length());
            int semi = rest.indexOf(';');
            if (semi < 0) throw new IllegalStateException("清单缺少签名段");
            byte[] jsonBytes = Base64.getDecoder().decode(rest.substring(0, semi));
            byte[] sigBytes = Base64.getDecoder().decode(rest.substring(semi + 1));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pub);
            verifier.update(jsonBytes);
            if (!verifier.verify(sigBytes)) {
                System.out.println("FAIL 清单签名无效（私钥与内置公钥不匹配或清单被篡改）");
                System.exit(1);
            }
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            if (!json.startsWith("{")) throw new IllegalStateException("清单不是 JSON");
            String body = json.substring(1, json.length() - 1);
            for (String pair : body.split(",")) {
                int c = pair.indexOf(':');
                if (c <= 0) continue;
                String k = unquote(pair.substring(0, c));
                String v = unquote(pair.substring(c + 1));
                if (k.equals(EXPIRE_FIELD)) { expire = v; continue; }
                if (k.equals(BUILD_FIELD)) { build = v; continue; }
                JarEntry je = jar.getJarEntry(k);
                if (je == null) throw new IllegalStateException("条目缺失: " + k);
                byte[] data;
                try (InputStream is = jar.getInputStream(je)) {
                    data = is.readAllBytes();
                }
                String got = toHex(MessageDigest.getInstance("SHA-256").digest(data));
                if (!got.equalsIgnoreCase(v)) {
                    System.out.println("FAIL 条目被篡改: " + k);
                    System.out.println("  expect=" + v);
                    System.out.println("  got   =" + got);
                    System.exit(1);
                }
                checked++;
            }
        }
        if (expire != null) {
            System.out.println("  内测版过期日期: " + expire
                    + (LocalDate.now().isAfter(LocalDate.parse(expire))
                    ? "（已过期）" : "（未过期）"));
        }
        if (build != null) {
            System.out.println("  构建号: " + build);
        }
        System.out.println("PASS 完整性校验通过（Ed25519 签名有效），共比对 " + checked + " 个条目");
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: BuildTools enc <明文> | genkeys | manifest <输入jar> <输出jar> [过期日期] | verify <jar>");
            System.exit(1);
        }
        switch (args[0]) {
            case "enc" -> {
                if (args.length < 2) { System.err.println("缺少明文"); System.exit(1); }
                System.out.println(en(args[1]));
            }
            case "genkeys" -> genKeys();
            case "manifest" -> {
                if (args.length < 3) { System.err.println("缺少 jar 路径"); System.exit(1); }
                buildManifest(args[1], args[2], args.length >= 4 ? args[3] : null);
            }
            case "verify" -> {
                if (args.length < 2) { System.err.println("缺少 jar 路径"); System.exit(1); }
                verifyJar(args[1]);
            }
            default -> { System.err.println("未知模式: " + args[0]); System.exit(1); }
        }
    }
}
