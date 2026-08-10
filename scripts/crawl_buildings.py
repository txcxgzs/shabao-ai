#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MC 建筑教程爬虫：从中文 Minecraft Wiki 爬取建筑布局/结构教程。

数据源：https://zh.minecraft.wiki/w/教程/{页面名}
输出：building_knowledge.json（建筑教程知识库，供 mod 加载）

与 block_knowledge.json 不同，本文件爬的是"建筑布局指导"：
- 楼梯间布局（直线/L型/U型/螺旋，尺寸、转角处理）
- 矿井结构（分支矿道、螺旋矿井、分支矿法）
- 庇护所类型（新手屋、农场、防御工事）
- 房屋形状（坡屋顶、平顶、多层结构）

使用方法：
    python scripts/crawl_buildings.py           # 爬取全部
    python scripts/crawl_buildings.py --test    # 只爬1个测试
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
    "User-Agent": "ShabaoAI-Crawler/1.0 (Minecraft Fabric mod building guide)",
    "Accept-Language": "zh-CN,zh;q=0.9",
}
REQUEST_INTERVAL = 1.5  # 请求间隔（秒），教程页面较大，间隔长一点
TIMEOUT = 30
MAX_RETRIES = 3
MAX_GUIDE_CHARS = 4000  # 单个教程文本上限，避免过长占用太多 token

# 建筑教程页面映射
# id: 简短英文标识（LLM 查询用）；page: wiki 页面名；category: 分类
# 注：部分教程 wiki 无对应页面，用手写条目（MANUAL_GUIDES）补充
BUILDING_PAGES = [
    # 建筑/庇护所/防御（wiki 存在的页面）
    {"id": "building", "page": "教程/建筑", "name": "建筑", "category": "建筑"},
    {"id": "shelter", "page": "教程/庇护所", "name": "庇护所", "category": "庇护所"},
    {"id": "defense", "page": "教程/防御", "name": "防御", "category": "防御"},
    {"id": "trap", "page": "教程/陷阱", "name": "陷阱", "category": "防御"},
]

# 手写建筑教程（wiki 无对应页面，基于 MC 建筑经验编写）
# 这些是建筑中最常出错的布局，必须包含详细尺寸和规则
MANUAL_GUIDES = [
    {
        "id": "stairwell",
        "name": "楼梯间布局",
        "category": "垂直交通",
        "guide": (
            "楼梯间是连接楼层的关键结构，布局错误会导致撞头、穿墙、无法上下。\n"
            "\n"
            "【类型1：直线楼梯】\n"
            "- 适合层高 ≤ 4 格的低层\n"
            "- 楼梯井尺寸：3×3 洞口，楼梯沿一边延伸\n"
            "- 每级楼梯 +1 y +1 水平方向，级数 = 层高\n"
            "- 每级头顶必须留 2 格净空（空气），否则撞天花板\n"
            "- 示例：层高4格，4级楼梯从 (0,1,0) 到 (0,4,3)\n"
            "\n"
            "【类型2：L型楼梯】\n"
            "- 适合转角建筑，2 段直线 + 转角平台\n"
            "- 楼梯井尺寸：3×3 或 5×5\n"
            "- 第一段走到转角，放平台方块（同高度），第二段转 90° 继续\n"
            "- 平台尺寸 1×1，玩家转向立足点\n"
            "- 每段级数 ≤ 3，避免超长直线\n"
            "\n"
            "【类型3：U型楼梯】\n"
            "- 适合层高 ≥ 6 格，两段平行 + 中间平台\n"
            "- 楼梯井尺寸：3×5（两段平行，中间留平台）\n"
            "- 第一段上 N 级，平台转 180°，第二段继续上 N 级\n"
            "- 平台高度 = 第一段顶部，尺寸 3×1\n"
            "\n"
            "【类型4：螺旋楼梯（推荐紧凑型）】\n"
            "- 2×2 footprint，最小占地，适合塔楼/小屋\n"
            "- 4个楼梯方块围成2×2，无中心柱\n"
            "- 每级正交相邻（共享一条边），玩家连续走每步上升1格\n"
            "- 4级完成一圈，上升4格\n"
            "- 逆时针位置循环：(0,0)→(1,0)→(1,1)→(0,1)\n"
            "- facing指向下一级方向：east→south→west→north\n"
            "  (0,0) facing=east  下一级(1,0)在东\n"
            "  (1,0) facing=south 下一级(1,1)在南\n"
            "  (1,1) facing=west  下一级(0,1)在西\n"
            "  (0,1) facing=north 下一级(0,0)在北(新圈)\n"
            "- 每级头顶留 2 格净空\n"
            "- 示例：12级=3圈，升12格，适合3层楼\n"
            "\n"
            "【关键规则】\n"
            "1. 楼梯必须放在楼梯井洞口内，不能放墙体位置\n"
            "2. 每级头顶必须 2 格空气（玩家 2 格高）\n"
            "3. 螺旋楼梯中心柱必须实心，整根柱子作支撑\n"
            "4. 直线楼梯级数 = 层高，不超过 6 级（太长用 U 型/螺旋）\n"
            "5. 楼梯井每层楼板用 floor shape 留洞口，尺寸匹配楼梯类型"
        ),
    },
    {
        "id": "spiral_mining",
        "name": "螺旋矿井",
        "category": "矿洞",
        "guide": (
            "螺旋矿井是一种高效的垂直采矿结构，能到达深层矿石同时保持通道畅通。\n"
            "\n"
            "【2×2 螺旋矿井（最小）】\n"
            "- footprint：2×2 竖井\n"
            "- 每挖 1 格深，在 2×2 的一角放楼梯方块，转 90°\n"
            "- 4 格深完成一圈，回到起点正下方\n"
            "- 优点：最省空间，适合单人\n"
            "- 缺点：无法放梯子/水流，只能靠楼梯上下\n"
            "\n"
            "【3×3 螺旋矿井（推荐）】\n"
            "- footprint：3×3 竖井，中心 1×1 空柱\n"
            "- 中心空柱可放梯子/水流作快速通道\n"
            "- 楼梯方块贴中心柱外缘，每级转 90°\n"
            "- 每级 +1 y，4 级一圈降 4 格\n"
            "- 优点：可兼作水电梯，效率高\n"
            "- 缺点：比 2×2 多挖一格宽\n"
            "\n"
            "【4×4 螺旋矿井（宽敞）】\n"
            "- footprint：4×4 竖井\n"
            "- 楼梯沿外缘螺旋下降，中心 2×2 空间\n"
            "- 可放梯子+水流+矿车轨道三套通道\n"
            "- 适合主基地矿井，多人共用\n"
            "\n"
            "【挖掘技巧】\n"
            "1. 从上往下挖，每级先放楼梯再挖下一级\n"
            "2. 每 5-6 格放火把照明，防刷怪\n"
            "3. 遇到岩浆立即停止，改道或填回\n"
            "4. 到达目标层后，改用分支矿法水平挖掘\n"
            "5. 螺旋矿井深度建议 ≤ 30 格，太深用直竖井+水流更快"
        ),
    },
    {
        "id": "branch_mining",
        "name": "分支矿法",
        "category": "矿洞",
        "guide": (
            "分支矿法（Branch Mining/Strip Mining）是最安全的水平采矿方式，暴露最多矿石同时避免岩浆和怪物。\n"
            "\n"
            "【基本布局】\n"
            "- 主矿道：1×2 隧道，水平挖掘\n"
            "- 分支：从主矿道两侧每隔 2-3 格向外挖 1×2 隧道\n"
            "- 分支长度：20-50 格，挖到尽头返回\n"
            "- 分支间隔：2 格（覆盖最多矿石）或 3 格（省镐子耐久）\n"
            "\n"
            "【最优挖掘高度】\n"
            "- 钻石：y=-58（1.18+ 版本），在 y=-53 到 y=-59 之间\n"
            "- 黄金：y=-16（普通）或 y=32（恶地生物群系）\n"
            "- 铁：y=15（峰值），y=-24 到 y=56 均有\n"
            "- 红石：y=-59（峰值）\n"
            "- 青金石：y=0（峰值）\n"
            "\n"
            "【安全规则】\n"
            "1. 每隔 5 格放火把，防刷怪\n"
            "2. 挖到岩浆立即填回（用圆石/泥土）\n"
            "3. 不要挖头顶（ gravel/sand 坍塌）和脚下（岩浆陷阱）\n"
            "4. 带 1 桶水（浇岩浆）、食物、备用镐子\n"
            "5. 挖到钻石/远古残骸先挖周围（矿石可能相邻）\n"
            "\n"
            "【效率优化】\n"
            "- 时运 III 镐子挖钻石/煤/青金石（产量×2-3）\n"
            "- 精准采集挖钻石矿石带回家用时运镐挖（安全）\n"
            "- 分支间隔 2 格时，每格方块都被暴露，覆盖率 100%\n"
            "- 分支间隔 3 格时，中间 1 格矿脉可能漏掉，但省 1/3 镐子耐久"
        ),
    },
    {
        "id": "cozy_house",
        "name": "温馨小屋布局",
        "category": "建筑",
        "guide": (
            "温馨小屋是玩家第一个家，需要兼顾防御、功能、美观。\n"
            "\n"
            "【尺寸建议】\n"
            "- 最小：5×5（单人基础，床+工作台+箱子）\n"
            "- 标准：7×7（含 Crafting 区、存储区、卧室）\n"
            "- 舒适：9×9（分区明确，可放装饰）\n"
            "- 层高：3-4 格（3 格紧凑，4 格宽敞不压抑）\n"
            "\n"
            "【功能分区】\n"
            "1. 入口区：门 + 压力板（自动关门），门口放火把\n"
            "2. 工作区：工作台 + 熔炉 + 烟熏炉（烹饪）+ 高炉（矿物）\n"
            "3. 存储区：箱子贴墙排列，按类型分类（矿物/食物/工具/建材）\n"
            "4. 卧室：床（靠墙），床头放床头箱/末影箱\n"
            "5. 农场区：室内小麦/胡萝卜 farm（需光照）\n"
            "\n"
            "【防御要点】\n"
            "- 墙壁用石头/圆石（防爆，僵尸难破）\n"
            "- 门用铁门+压力板（僵尸不会开铁门）\n"
            "- 窗户用玻璃板（透光但不透怪）\n"
            "- 屋顶封顶（防蜘蛛/幻翼）\n"
            "- 四周挖 2 格深沟或放栅栏（防怪物靠近）\n"
            "\n"
            "【美观技巧】\n"
            "- 墙体混搭：圆石+石砖+木板纹理\n"
            "- 屋顶用楼梯方块做斜坡（橡木楼梯/石砖楼梯）\n"
            "- 屋檐突出 1 格（外墙顶放反向楼梯）\n"
            "- 窗户加活板门作窗框（装饰）\n"
            "- 室内放花盆、画、灯笼（温馨感）\n"
            "- 门廊：入口前伸 2 格屋顶，放柱子支撑\n"
            "\n"
            "【常见错误】\n"
            "1. 屋顶不封顶 → 蜘蛛爬进/幻翼袭击\n"
            "2. 门朝外开 → 开门时被怪偷袭\n"
            "3. 箱子贴床 → 无法打开（床挡住箱子界面）\n"
            "4. 熔炉贴墙 → 烧炼时烟雾阻挡（需烟囱）\n"
            "5. 无备用出口 → 被困时无法逃生"
        ),
    },
]


# ============ 爬取函数 ============

def fetch_page(page_name):
    """获取 wiki 页面 HTML，带重试机制。

    Args:
        page_name: wiki 页面名（如"教程/楼梯间"）
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


def extract_guide(soup):
    """提取教程页面的正文指导文本。

    教程页面主要是文字段落，遍历 mw-parser-output 下的 <p> 和 <li>，
    过滤导航、目录、过短片段，合并为完整教程文本。

    提取策略：
    1. 找到 mw-parser-output 主内容区
    2. 遍历所有 <p>（段落）和 <li>（列表项）
    3. 过滤：长度<10字、以"导航"/"分类"/"教程"开头（噪声）
    4. 合并，上限 MAX_GUIDE_CHARS 字符

    Returns:
        str: 教程指导文本，或空字符串
    """
    body = soup.find("div", class_="mw-parser-output") or soup

    # 噪声前缀过滤
    NOISE_PREFIXES = ("导航", "分类", "教程导航", "参见", "参考", "外部链接")

    texts = []
    total_len = 0
    for el in body.find_all(["p", "li"]):
        t = el.get_text(strip=True)
        # 过滤太短的、噪声、引用标记
        if not t or len(t) < 10:
            continue
        if any(t.startswith(p) for p in NOISE_PREFIXES):
            continue
        # 过滤纯链接行（如"[编辑]"）
        if t.startswith("[") and t.endswith("]"):
            continue
        texts.append(t)
        total_len += len(t)
        # 达到上限就停止，避免单个教程太长
        if total_len >= MAX_GUIDE_CHARS:
            break

    return "\n".join(texts)


def crawl_page(page_info):
    """爬取单个建筑教程页面。

    Args:
        page_info: {"id": ..., "page": ..., "name": ..., "category": ...}
    Returns:
        dict: 教程条目，或 None
    """
    page_name = page_info["page"]
    print(f"爬取: {page_name} ({page_info['category']})")

    soup = fetch_page(page_name)
    if not soup:
        return None

    guide = extract_guide(soup)
    if not guide:
        print(f"  [警告] 未提取到内容: {page_name}")
        return None

    print(f"  指导文本 {len(guide)} 字")
    return {
        "id": page_info["id"],
        "name": page_info["name"],
        "category": page_info["category"],
        "guide": guide,
    }


def crawl_all(test_mode=False):
    """爬取所有建筑教程页面 + 合并手写教程，生成知识库 JSON。"""
    pages = BUILDING_PAGES[:1] if test_mode else BUILDING_PAGES
    guides = []
    failed = []

    # 1. 爬取 wiki 页面
    for i, page_info in enumerate(pages):
        entry = crawl_page(page_info)
        if entry:
            guides.append(entry)
        else:
            failed.append(page_info["page"])
        if i < len(pages) - 1:
            time.sleep(REQUEST_INTERVAL)

    # 2. 合并手写教程（wiki 无对应页面的关键布局知识）
    if not test_mode:
        for manual in MANUAL_GUIDES:
            print(f"合并手写教程: {manual['name']} ({manual['category']})  {len(manual['guide'])} 字")
            guides.append(manual)

    knowledge = {
        "version": 1,
        "source": "https://zh.minecraft.wiki/ + 手写建筑经验",
        "description": "Minecraft 建筑教程知识库（楼梯间、矿洞、庇护所等布局指导）",
        "guide_count": len(guides),
        "guides": guides,
    }

    if failed:
        print(f"\n失败页面: {failed}")

    return knowledge


def main():
    test_mode = "--test" in sys.argv
    print(f"=== MC 建筑教程爬虫 ===")
    print(f"模式: {'测试' if test_mode else '全量'}")
    print(f"待爬取页面: {1 if test_mode else len(BUILDING_PAGES)} 个")
    print()

    knowledge = crawl_all(test_mode=test_mode)

    # 输出路径：mod 资源目录
    output_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "src", "main", "resources", "building_knowledge.json"
    )
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(knowledge, f, ensure_ascii=False, indent=2)

    print(f"\n=== 完成 ===")
    print(f"成功: {knowledge['guide_count']} 个教程")
    print(f"输出: {output_path}")


if __name__ == "__main__":
    main()
