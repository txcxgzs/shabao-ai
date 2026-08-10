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

import java.util.jar.JarFile;

/**
 * 【防篡改·字节码验证器】用 JVM 自身的类加载机制验证混淆后 jar 内所有类的字节码
 * 是否合法（捕获 VerifyError）。用于构建期发现 ProGuard 生成的非法字节码
 * （如 multi-catch 合成异常类型导致的 StackMapTable 损坏）。
 *
 * <p>用法：{@code ByteVerify <obf.jar>}，classpath 需包含模组全部依赖
 * （Minecraft/Fabric/Gson 等），否则依赖缺失类会被跳过而非验证失败。
 */
public final class ByteVerify {

    private ByteVerify() {}

    public static void main(String[] args) throws Exception {
        String jarPath = args[0];
        int verified = 0;
        int skipped = 0;
        try (JarFile jar = new JarFile(jarPath)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                String cn = name.substring(0, name.length() - 6).replace('/', '.');
                try {
                    // false = 只加载+验证，不触发静态初始化（避免 side effect）
                    Class.forName(cn, false, ClassLoader.getSystemClassLoader());
                    verified++;
                } catch (VerifyError e) {
                    System.out.println("[VERIFY-FAIL] " + cn + " -> " + e);
                    System.exit(1);
                } catch (Throwable t) {
                    // 依赖缺失/环境差异：不视为字节码非法，记录后继续
                    skipped++;
                    System.out.println("[skip] " + cn + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        }
        System.out.println("[ByteVerify] OK: 验证 " + verified + " 个类，跳过 " + skipped + " 个，无 VerifyError");
    }
}
