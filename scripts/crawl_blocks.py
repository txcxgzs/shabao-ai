#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MC 方块百科爬虫：从中文 Minecraft Wiki 爬取建筑相关方块的百科内容。

数据源：https://zh.minecraft.wiki/w/{页面名}
输出：block_knowledge.json（详细方块知识库，供 mod 加载）

爬取内容：
1. 用途说明（"用途"章节段落文本，含放置规则、红石行为等）
2. 方块状态表格（属性名、默认值、接受值、描述）
3. 获取方式摘要（"获取"章节，含合成/自然生成）

使用方法：
    python scripts/crawl_blocks.py           # 爬取全部
    python scripts/crawl_blocks.py --test    # 只爬1个测试
"""

import requests
from bs4 import BeautifulSoup
import json
import time
import sys
import os
from urllib.parse import quote

# ============ 配置 ============

WIKI_BASE = "https://zh.minecraft.wiki/w/"
HEADERS = {
    "User-Agent": "ShabaoAI-Crawler/1.0 (Minecraft Fabric mod block knowledge)",
    "Accept-Language": "zh-CN,zh;q=0.9",
}
REQUEST_INTERVAL = 1.0  # 请求间隔（秒），避免给 wiki 服务器压力
TIMEOUT = 30
MAX_RETRIES = 3

# 建筑相关方块的 wiki 页面映射
# 每个条目：wiki页面名 -> 包含的方块ID列表 + 分类
BLOCK_PAGES = [
    # 门类
    {"page": "木门", "category": "门", "ids": [
        "oak_door", "spruce_door", "birch_door", "jungle_door", "acacia_door",
        "dark_oak_door", "mangrove_door", "cherry_door", "bamboo_door",
        "crimson_door", "warped_door"
    ]},
    {"page": "铁门", "category": "门", "ids": ["iron_door"]},
    {"page": "活板门", "category": "门", "ids": [
        "oak_trapdoor", "spruce_trapdoor", "birch_trapdoor", "jungle_trapdoor",
        "acacia_trapdoor", "dark_oak_trapdoor", "mangrove_trapdoor", "cherry_trapdoor",
        "iron_trapdoor"
    ]},
    {"page": "栅栏门", "category": "门", "ids": [
        "oak_fence_gate", "spruce_fence_gate", "birch_fence_gate", "jungle_fence_gate",
        "acacia_fence_gate", "dark_oak_fence_gate", "mangrove_fence_gate", "cherry_fence_gate",
        "bamboo_fence_gate", "crimson_fence_gate", "warped_fence_gate"
    ]},
    # 楼梯台阶
    {"page": "楼梯", "category": "楼梯", "ids": [
        "oak_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs", "acacia_stairs",
        "dark_oak_stairs", "mangrove_stairs", "cherry_stairs", "bamboo_stairs",
        "cobblestone_stairs", "stone_stairs", "stone_brick_stairs", "brick_stairs",
        "nether_brick_stairs", "sandstone_stairs", "quartz_stairs", "purpur_stairs",
        "prismarine_stairs", "dark_prismarine_stairs", "granite_stairs", "diorite_stairs",
        "andesite_stairs", "blackstone_stairs", "deepslate_brick_stairs"
    ]},
    {"page": "台阶", "category": "台阶", "ids": [
        "oak_slab", "spruce_slab", "birch_slab", "stone_slab", "cobblestone_slab",
        "stone_brick_slab", "brick_slab", "nether_brick_slab", "quartz_slab",
        "sandstone_slab", "purpur_slab", "prismarine_slab", "granite_slab",
        "andesite_slab", "blackstone_slab", "deepslate_brick_slab"
    ]},
    # 梯子
    {"page": "梯子", "category": "梯子", "ids": ["ladder"]},
    # 红石元件
    {"page": "压力板", "category": "红石", "ids": [
        "stone_pressure_plate", "oak_pressure_plate", "spruce_pressure_plate",
        "birch_pressure_plate", "jungle_pressure_plate", "acacia_pressure_plate",
        "dark_oak_pressure_plate", "mangrove_pressure_plate", "cherry_pressure_plate",
        "bamboo_pressure_plate", "crimson_pressure_plate", "warped_pressure_plate",
        "light_weighted_pressure_plate", "heavy_weighted_pressure_plate",
        "polished_blackstone_pressure_plate"
    ]},
    {"page": "按钮", "category": "红石", "ids": [
        "stone_button", "oak_button", "spruce_button", "birch_button", "jungle_button",
        "acacia_button", "dark_oak_button", "mangrove_button", "cherry_button",
        "bamboo_button", "crimson_button", "warped_button", "polished_blackstone_button"
    ]},
    {"page": "拉杆", "category": "红石", "ids": ["lever"]},
    # 光源
    {"page": "火把", "category": "光源", "ids": ["torch", "wall_torch", "soul_torch", "soul_wall_torch"]},
    {"page": "灯笼", "category": "光源", "ids": ["lantern", "soul_lantern"]},
    {"page": "萤石", "category": "光源", "ids": ["glowstone"]},
    {"page": "海晶灯", "category": "光源", "ids": ["sea_lantern"]},
    {"page": "菌光体", "category": "光源", "ids": ["shroomlight"]},
    {"page": "末地烛", "category": "光源", "ids": ["end_rod"]},
    # 窗户
    {"page": "玻璃板", "category": "窗户", "ids": [
        "glass_pane", "white_stained_glass_pane", "orange_stained_glass_pane",
        "magenta_stained_glass_pane", "light_blue_stained_glass_pane", "yellow_stained_glass_pane",
        "lime_stained_glass_pane", "pink_stained_glass_pane", "gray_stained_glass_pane",
        "light_gray_stained_glass_pane", "cyan_stained_glass_pane", "purple_stained_glass_pane",
        "blue_stained_glass_pane", "brown_stained_glass_pane", "green_stained_glass_pane",
        "red_stained_glass_pane", "black_stained_glass_pane"
    ]},
    # 护栏
    {"page": "栅栏", "category": "护栏", "ids": [
        "oak_fence", "spruce_fence", "birch_fence", "jungle_fence", "acacia_fence",
        "dark_oak_fence", "mangrove_fence", "cherry_fence", "bamboo_fence",
        "nether_brick_fence", "crimson_fence", "warped_fence"
    ]},
    {"page": "墙", "category": "护栏", "ids": [
        "cobblestone_wall", "mossy_cobblestone_wall", "stone_brick_wall",
        "mossy_stone_brick_wall", "granite_wall", "diorite_wall", "andesite_wall",
        "sandstone_wall", "red_sandstone_wall", "brick_wall", "nether_brick_wall",
        "red_nether_brick_wall", "end_stone_brick_wall", "blackstone_wall",
        "polished_blackstone_wall", "polished_blackstone_brick_wall",
        "cobbled_deepslate_wall", "polished_deepslate_wall", "deepslate_brick_wall",
        "deepslate_tile_wall"
    ]},
    # 装饰
    {"page": "地毯", "category": "装饰", "ids": [
        "white_carpet", "orange_carpet", "magenta_carpet", "light_blue_carpet",
        "yellow_carpet", "lime_carpet", "pink_carpet", "gray_carpet",
        "light_gray_carpet", "cyan_carpet", "purple_carpet", "blue_carpet",
        "brown_carpet", "green_carpet", "red_carpet", "black_carpet"
    ]},
    # 家具
    {"page": "床", "category": "家具", "ids": [
        "red_bed", "orange_bed", "yellow_bed", "green_bed", "blue_bed", "purple_bed"
    ]},
]


# ============ 爬取函数 ============

def fetch_page(page_name):
    """获取 wiki 页面 HTML，带重试机制。

    Args:
        page_name: wiki 页面名（中文，如"木门"）
    Returns:
        BeautifulSoup 对象，或 None（失败时）
    """
    url = WIKI_BASE + quote(page_name)
    for attempt in range(MAX_RETRIES):
        try:
            r = requests.get(url, headers=HEADERS, timeout=TIMEOUT)
            if r.status_code == 200:
                return BeautifulSoup(r.text, "html.parser")
            elif r.status_code == 404:
                print(f"  [404] 页面不存在: {page_name}")
                return None
            else:
                print(f"  [{r.status_code}] 重试 {attempt+1}/{MAX_RETRIES}: {page_name}")
        except requests.RequestException as e:
            print(f"  [错误] 重试 {attempt+1}/{MAX_RETRIES}: {e}")
        time.sleep(2)
    return None


def extract_usage(soup):
    """提取"用途"章节的段落文本。

    遍历 h2 id="用途" 之后的所有 <p>，直到遇到下一个 h2。
    提取所有有意义的段落（>5字），合并为用途说明。

    Returns:
        str: 用途说明文本（多段用换行分隔），或空字符串
    """
    body = soup.find("div", class_="mw-parser-output") or soup
    h2 = body.find("h2", id="用途")
    if not h2:
        # 有些页面用"放置"作为章节名
        h2 = body.find("h2", id="放置")
    if not h2:
        return ""

    texts = []
    for el in h2.find_all_next(["p", "h2", "h3"]):
        if el.name == "h2":
            break
        if el.name == "p":
            t = el.get_text(strip=True)
            # 过滤太短的片段（如单独的引用标记）
            if t and len(t) > 5:
                texts.append(t)
    return "\n".join(texts)


def parse_table_with_rowspan(tbl):
    """解析带 rowspan/colspan 的 wiki 表格为二维数组。

    wiki 表格常用 rowspan 合并单元格（如属性名占4行），
    本方法把合并单元格展开成完整的二维数组，每行列数一致。

    Returns:
        list[list[str]]: 二维数组，matrix[行][列] = 单元格文本
    """
    rows = tbl.find_all("tr")
    matrix = []
    pending = {}  # col_idx -> (text, remaining_rows) 继承的单元格

    for tr in rows:
        cells = tr.find_all(["th", "td"])
        row = []
        col = 0

        def fill_inherited():
            """填充继承的 rowspan 单元格"""
            nonlocal col
            while col in pending:
                text, remaining = pending[col]
                row.append(text)
                if remaining <= 1:
                    del pending[col]
                else:
                    pending[col] = (text, remaining - 1)
                col += 1

        fill_inherited()
        for cell in cells:
            fill_inherited()
            text = cell.get_text(strip=True)
            rowspan = int(cell.get("rowspan", 1))
            colspan = int(cell.get("colspan", 1))
            for _ in range(colspan):
                row.append(text)
                if rowspan > 1:
                    pending[col] = (text, rowspan - 1)
                col += 1
        fill_inherited()
        matrix.append(row)
    return matrix


def extract_block_states(soup):
    """提取"方块状态"章节的属性表格。

    找到 h3 id="方块状态"，取其后第一个 wikitable，解析成属性列表。
    表格格式：方块 | 方块属性 | 默认值 | 接受值 | 描述
    使用 parse_table_with_rowspan 正确处理 rowspan 合并单元格。

    Returns:
        list[dict]: 属性列表，每项 {name, default, values, desc}，或空列表
    """
    body = soup.find("div", class_="mw-parser-output") or soup
    # 方块状态章节可能是 h2 或 h3
    h = body.find("h3", id="方块状态") or body.find("h2", id="方块状态")
    if not h:
        return []

    tbl = h.find_next("table", class_="wikitable")
    if not tbl:
        return []

    matrix = parse_table_with_rowspan(tbl)
    if len(matrix) < 2:
        return []

    # 表头：方块(0) | 方块属性(1) | 默认值(2) | 接受值(3) | 描述(4)
    properties = []
    current_prop = None

    for row in matrix[1:]:  # 跳过表头
        if len(row) < 5:
            continue
        prop_name = row[1]
        default = row[2]
        accepted = row[3]
        desc = row[4]

        # 跳过空属性行或表头重复
        if not prop_name or prop_name in ("方块", "方块属性"):
            continue

        # 同一属性可能有多个接受值行（rowspan 已展开，属性名会重复）
        if current_prop and prop_name == current_prop["name"]:
            if accepted and accepted not in current_prop["values"]:
                current_prop["values"].append(accepted)
        else:
            if current_prop:
                properties.append(current_prop)
            current_prop = {
                "name": prop_name,
                "default": default,
                "values": [accepted] if accepted else [],
                "desc": desc
            }

    if current_prop:
        properties.append(current_prop)
    return properties


def extract_obtaining(soup):
    """提取"获取"章节的摘要（合成方式、自然生成等）。

    同时提取段落 <p> 和列表 <li> 内容，因为合成配方常以列表呈现。

    Returns:
        str: 获取方式摘要，或空字符串
    """
    body = soup.find("div", class_="mw-parser-output") or soup
    h2 = body.find("h2", id="获取")
    if not h2:
        return ""
    texts = []
    for el in h2.find_all_next(["p", "h2", "h3", "li"]):
        if el.name == "h2":
            break
        if el.name in ("p", "li"):
            t = el.get_text(strip=True)
            # 过滤太短的内容和导航文字
            if t and len(t) > 5 and not t.startswith("导航"):
                texts.append(t)
    return "\n".join(texts[:5])  # 取前5条，避免太长


def crawl_page(page_info):
    """爬取单个方块类别页面，提取百科内容。

    Args:
        page_info: {"page": wiki页面名, "category": 分类, "ids": [方块ID列表]}
    Returns:
        dict: 方块知识条目，或 None
    """
    page_name = page_info["page"]
    print(f"爬取: {page_name} ({page_info['category']})")

    soup = fetch_page(page_name)
    if not soup:
        return None

    usage = extract_usage(soup)
    properties = extract_block_states(soup)
    obtaining = extract_obtaining(soup)

    if not usage and not properties:
        print(f"  [警告] 未提取到内容: {page_name}")
        return None

    print(f"  用途 {len(usage)} 字, 属性 {len(properties)} 个, 获取 {len(obtaining)} 字")

    return {
        "ids": page_info["ids"],
        "name": page_name,
        "category": page_info["category"],
        "usage": usage,
        "obtaining": obtaining,
        "properties": properties,
    }


def crawl_all(test_mode=False):
    """爬取所有方块页面，生成知识库 JSON。

    Args:
        test_mode: True 只爬第一个页面测试
    Returns:
        dict: 完整知识库
    """
    pages = BLOCK_PAGES[:1] if test_mode else BLOCK_PAGES
    blocks = []
    failed = []

    for i, page_info in enumerate(pages):
        entry = crawl_page(page_info)
        if entry:
            blocks.append(entry)
        else:
            failed.append(page_info["page"])

        # 请求间隔（最后一个不等）
        if i < len(pages) - 1:
            time.sleep(REQUEST_INTERVAL)

    knowledge = {
        "version": 2,
        "source": "https://zh.minecraft.wiki/",
        "description": "Minecraft 1.21 建筑相关方块百科知识库（从中文MC Wiki爬取）",
        "block_count": len(blocks),
        "blocks": blocks,
    }

    if failed:
        print(f"\n失败页面: {failed}")

    return knowledge


def main():
    test_mode = "--test" in sys.argv
    print(f"=== MC 方块百科爬虫 ===")
    print(f"模式: {'测试' if test_mode else '全量'}")
    print(f"待爬取页面: {1 if test_mode else len(BLOCK_PAGES)} 个")
    print()

    knowledge = crawl_all(test_mode=test_mode)

    # 输出路径：mod 资源目录
    output_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "src", "main", "resources", "block_knowledge.json"
    )
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(knowledge, f, ensure_ascii=False, indent=2)

    print(f"\n=== 完成 ===")
    print(f"成功: {knowledge['block_count']} 个方块类别")
    print(f"输出: {output_path}")
    total_ids = sum(len(b["ids"]) for b in knowledge["blocks"])
    print(f"覆盖方块ID: {total_ids} 个")


if __name__ == "__main__":
    main()
