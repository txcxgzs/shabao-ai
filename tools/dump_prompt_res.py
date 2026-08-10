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

# 一次性工具：解密 ai_prompt.dat 的 5 个条目，写入 prompts/ 明文模板目录（该目录不入 jar）。
# 用法：python tools/dump_prompt_res.py
# 跑完后此脚本可删除（它只是把密文还原成可编辑的明文源）。
import os, subprocess, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DAT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'shabao_ai', 'ai_prompt.dat')
OUTDIR = os.path.join(ROOT, 'prompts')
BUILDTOOLS = os.path.join(ROOT, 'build', 'buildtools')

# 条目键 -> 输出文件名
NAME_MAP = {'PROMPT': 'prompt.txt', 'EFFORT_LOW': 'effort_low.txt',
            'EFFORT_MED': 'effort_med.txt', 'EFFORT_MAX': 'effort_max.txt',
            'PROMPT_EXAMPLE': 'prompt_example.txt'}

entries = {}
for line in open(DAT, encoding='utf-8'):
    line = line.rstrip('\n')
    if not line or '=' not in line:
        continue
    k, _, c = line.partition('=')
    entries[k.strip()] = c.strip()

missing = [k for k in NAME_MAP if k not in entries]
if missing:
    raise SystemExit('ai_prompt.dat 缺少条目: ' + ', '.join(missing))

tmpdir = tempfile.mkdtemp()
runner = os.path.join(tmpdir, 'DumpPrompt.java')
open(runner, 'w', encoding='utf-8').write('''
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public final class DumpPrompt {
    public static void main(String[] a) throws Exception {
        // 参数格式：cipher 写路径（每两个参数一对），明文直接写 UTF-8 文件，避免 stdout 编码转换
        Method de = BuildTools.class.getDeclaredMethod("de", String.class);
        de.setAccessible(true);
        for (int i = 0; i < a.length; i += 2) {
            String plain = (String) de.invoke(null, a[i]);
            Files.writeString(Path.of(a[i + 1]), plain, StandardCharsets.UTF_8);
        }
    }
}
''')
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', BUILDTOOLS, '-d', tmpdir, runner], check=True)

os.makedirs(OUTDIR, exist_ok=True)
args = []
for k in NAME_MAP:
    args.append(entries[k])
    args.append(os.path.join(OUTDIR, NAME_MAP[k]))
subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', tmpdir + ';' + BUILDTOOLS, 'DumpPrompt'] + args, check=True)

for k, fname in NAME_MAP.items():
    p = os.path.join(OUTDIR, fname)
    print('已还原 %s -> prompts/%s (%d chars)' % (k, fname, len(open(p, encoding='utf-8').read())))
print('提示：prompts/ 目录明文不进 jar；改完模板后跑 gen_prompt_res.py 重新加密。')
