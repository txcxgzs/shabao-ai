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

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;

/**
 * 把本模组接入 ModMenu：玩家在模组列表里点 Shabao AI Companion 的"配置"按钮，
 * ModMenu 就会调用 getModConfigScreenFactory() 拿到我们的配置屏。
 *
 * <p>实际配置 UI 由 Cloth Config 渲染（见 {@link ShabaoConfigScreen}），
 * 这里只负责"提供 Screen 工厂"这一层薄包装。
 */
public final class ShabaoAiModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
        return ShabaoConfigScreen::create;
    }
}
