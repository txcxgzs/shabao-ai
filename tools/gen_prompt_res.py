# Copyright (C) 2026 txcxgzs
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
#

# 生成 ai_prompt.dat：把 AgentExecutor 的核心提示词模板/思考纪律/示例 JSON 加密为资源文件。
# 明文源在 prompts/ 目录（该目录不入 jar，反编译拿不到明文）：
#   prompts/prompt.txt        主 system prompt 模板（含 %s 思考纪律 / %d 步数上限占位符）
#   prompts/effort_low.txt    思考纪律（低档·硬限制）
#   prompts/effort_med.txt    思考纪律（中等）
#   prompts/effort_max.txt    思考纪律（深度）
#   prompts/prompt_example.txt  build_plan 完整示例 JSON（verifyPromptExamples 自检对象）
# 运行：python tools/gen_prompt_res.py（输出 src/main/resources/assets/shabao_ai/ai_prompt.dat）
# 改提示词后必须重跑本脚本，否则运行时解密的是旧模板。
# 首次迁移：若 prompts/ 不存在，可先跑 tools/dump_prompt_res.py 从旧 ai_prompt.dat 还原明文。
import os, subprocess, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROMPTS = os.path.join(ROOT, 'prompts')
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'shabao_ai', 'ai_prompt.dat')

FILE_MAP = {
    'PROMPT': 'prompt.txt',
    'EFFORT_LOW': 'effort_low.txt',
    'EFFORT_MED': 'effort_med.txt',
    'EFFORT_MAX': 'effort_max.txt',
    'PROMPT_EXAMPLE': 'prompt_example.txt',
}

plain = {}
for key, fname in FILE_MAP.items():
    p = os.path.join(PROMPTS, fname)
    if not os.path.isfile(p):
        raise SystemExit('缺少明文模板 prompts/%s —— 先跑 tools/dump_prompt_res.py 从旧 ai_prompt.dat 还原' % fname)
    plain[key] = open(p, encoding='utf-8').read()

# 【防傻自检·编码护栏】明文模板必须含足量 CJK 中文字符。
# 历史教训：若某步构建用错误 charset 把中文写成 '?'，解密比对自检也会"一致"地假 PASS
# （模板与密文都是 '?'，逐字符相等）。这里直接数中文字符数——中文规则是这套 Prompt 的
# 行为核心（长期 Agent/静默/建筑纪律），CJK 归零 = 模板已烂，构建必须失败。
def _cjk_count(s):
    return sum(1 for ch in s if '\u4e00' <= ch <= '\u9fff' or '\u3400' <= ch <= '\u4dbf')

for key, val in plain.items():
    cjk = _cjk_count(val)
    if cjk < 10:
        raise SystemExit('【编码护栏】模板 %s 中文字符数=%d，疑似被错误编码写成 ?。'
                         '请检查 prompts/%s 是否为 UTF-8 明文' % (key, cjk, FILE_MAP[key]))
    if '????????' in val:
        raise SystemExit('【编码护栏】模板 %s 含 ????????，疑似中文已损坏' % key)
    print('  模板 %s CJK=%d（%d chars）' % (key, cjk, len(val)))

# 主模板占位符自检：%s（思考纪律）与 %d（步数上限）必须存在，且顺序正确（formatted 注入）
prompt = plain['PROMPT']
if '%s' not in prompt or '%d' not in prompt:
    raise SystemExit('PROMPT 模板缺少占位符：需要 %s（思考纪律）与 %d（步数上限）')
if prompt.index('%s') > prompt.index('%d'):
    raise SystemExit('PROMPT 模板占位符顺序错误：%s 必须在 %d 之前')
if '【方向真值表】' not in prompt:
    raise SystemExit('PROMPT 模板缺少自检锚点【方向真值表】（buildSystemPrompt 依赖它做模板注入校验）')

# 用编译好的 BuildTools 加密（A2），逐段生成密文
tmpdir = tempfile.mkdtemp()
buildtools = os.path.join(ROOT, 'build', 'buildtools')
if not os.path.isdir(buildtools):
    raise SystemExit('先执行 gradle compileBuildTools 或 javac 编译 tools/*.java 到 build/buildtools')
enc_paths = []
for key, val in plain.items():
    p = os.path.join(tmpdir, key + '.txt')
    open(p, 'w', encoding='utf-8', newline='').write(val)
    enc_paths.append(p)

runner = os.path.join(tmpdir, 'GenPromptEnc.java')
open(runner, 'w', encoding='utf-8').write('''
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public final class GenPromptEnc {
    public static void main(String[] a) throws Exception {
        Method en = BuildTools.class.getDeclaredMethod("en", String.class);
        en.setAccessible(true);
        for (String f : a) {
            String plain = Files.readString(Path.of(f), StandardCharsets.UTF_8);
            System.out.println(Files.readString(Path.of(f)).length() > 0 ? en.invoke(null, plain) : "");
        }
    }
}
''')
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', buildtools, '-d', tmpdir, runner], check=True)
res = subprocess.run(['java', '-cp', tmpdir + ';' + buildtools, 'GenPromptEnc'] + enc_paths,
                     check=True, capture_output=True, text=True)
ciphers = res.stdout.strip().split('\n')
keys = list(plain)
assert len(ciphers) == len(keys), '密文数量不匹配'

with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
    for k, c in zip(keys, ciphers):
        f.write(k + '=' + c + '\n')
print('已生成', OUT)
for k, v in plain.items():
    print('  %s: %d chars -> %d chars cipher' % (k, len(v), len(ciphers[keys.index(k)])))

# 【防傻自检·密文回验】生成后立即解密数 CJK，与明文模板逐条核对。
# 密文是 Base64（stdout 捕获不丢字节），理论上无损；但防御性验证最终产物，
# 杜绝任何环节（模板读取/Java 加密/输出捕获/写文件）悄悄把中文写成 '?'。
check = os.path.join(tmpdir, 'CjkCheck.java')
open(check, 'w', encoding='utf-8').write('''
import java.lang.reflect.Method;
public final class CjkCheck {
    public static void main(String[] a) throws Exception {
        Method de = BuildTools.class.getDeclaredMethod("de", String.class);
        de.setAccessible(true);
        for (int i = 0; i < a.length; i += 2) {
            String plain = (String) de.invoke(null, a[i]);
            long cjk = plain.chars().filter(c -> (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)).count();
            System.out.println(a[i + 1] + "=" + cjk);
        }
    }
}
''')
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', buildtools, '-d', tmpdir, check], check=True)
args = []
for k in keys:
    args += [ciphers[keys.index(k)], k]
rv = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', tmpdir + ';' + buildtools, 'CjkCheck'] + args,
                    check=True, capture_output=True, text=True)
for line in rv.stdout.strip().split('\n'):
    if not line:
        continue
    k, _, c = line.partition('=')
    expect = _cjk_count(plain[k])
    if int(c) != expect:
        raise SystemExit('【密文回验失败】%s 解密 CJK=%s 与明文模板 %d 不一致——构建链编码损坏' % (k, c, expect))
    print('  密文回验 %s CJK=%s（与明文一致）' % (k, c))
