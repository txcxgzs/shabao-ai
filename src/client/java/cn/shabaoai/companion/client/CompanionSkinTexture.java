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

package cn.shabaoai.companion.client;

import cn.shabaoai.companion.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 客户端本地皮肤加载器：64x64 PNG、按配置切换、同路径文件热重载。 */
final class CompanionSkinTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("shabao_ai/skin");
    private static final long CHECK_INTERVAL_NANOS = 1_000_000_000L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;

    private static String activeKey = "";
    private static long activeModified = Long.MIN_VALUE;
    private static long nextCheckAt;
    private static Identifier dynamicTexture;
    private static String lastErrorKey = "";

    private CompanionSkinTexture() {}

    static Identifier getOrFallback(Identifier fallback) {
        ModConfig config = ModConfig.get();
        String builtInFile = ModConfig.BUILT_IN_SKINS.contains(config.builtInSkin)
                ? config.builtInSkin : ModConfig.DEFAULT_BUILT_IN_SKIN;
        Identifier builtInTexture = Identifier.of("shabao_ai",
                "textures/entity/skins/" + builtInFile);
        String pathText = config.companionSkinPath == null ? "" : config.companionSkinPath.trim();
        String key = config.customSkinEnabled + "|" + builtInFile + "|" + pathText;
        long now = System.nanoTime();

        if (!config.customSkinEnabled || pathText.isBlank()) {
            if (!key.equals(activeKey)) clearDynamicTexture();
            activeKey = key;
            activeModified = Long.MIN_VALUE;
            return builtInTexture;
        }
        if (key.equals(activeKey) && now < nextCheckAt) {
            return dynamicTexture == null ? builtInTexture : dynamicTexture;
        }

        Path path;
        try {
            path = Path.of(pathText);
            if (!path.isAbsolute()) path = FabricLoader.getInstance().getConfigDir().resolve(path);
            path = path.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            activeKey = key;
            nextCheckAt = now + CHECK_INTERVAL_NANOS;
            reportOnce(key, "无效的皮肤路径: " + pathText, e);
            return builtInTexture;
        }

        nextCheckAt = now + CHECK_INTERVAL_NANOS;

        try {
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("文件不存在");
            long size = Files.size(path);
            if (size <= 0 || size > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("文件大小必须在 1 B 到 4 MiB 之间");
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (key.equals(activeKey) && dynamicTexture != null && modified == activeModified) {
                return dynamicTexture;
            }

            NativeImage image;
            try (InputStream in = Files.newInputStream(path)) {
                image = NativeImage.read(in);
            }
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                int width = image.getWidth();
                int height = image.getHeight();
                image.close();
                throw new IllegalArgumentException("尺寸必须是 64x64，当前为 " + width + "x" + height);
            }

            TextureManager textures = MinecraftClient.getInstance().getTextureManager();
            NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
            Identifier replacement;
            try {
                replacement = textures.registerDynamicTexture("shabao_custom_skin", texture);
            } catch (RuntimeException e) {
                texture.close();
                throw e;
            }
            Identifier previous = dynamicTexture;
            dynamicTexture = replacement;
            activeKey = key;
            activeModified = modified;
            lastErrorKey = "";
            if (previous != null) textures.destroyTexture(previous);
            LOGGER.info("已加载自定义伙伴皮肤: {}", path);
            return dynamicTexture;
        } catch (Exception e) {
            if (!key.equals(activeKey)) clearDynamicTexture();
            activeKey = key;
            activeModified = Long.MIN_VALUE;
            reportOnce(key + "|" + path, "外部伙伴皮肤加载失败，已回退所选内置皮肤: " + path, e);
            return builtInTexture;
        }
    }

    private static void clearDynamicTexture() {
        if (dynamicTexture != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(dynamicTexture);
            dynamicTexture = null;
        }
    }

    private static void reportOnce(String key, String message, Exception error) {
        if (key.equals(lastErrorKey)) return;
        lastErrorKey = key;
        LOGGER.warn(message + " ({})", error.getMessage());
    }
}
