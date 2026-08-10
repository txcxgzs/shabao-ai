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

# 一次性验证：解密 ai_prompt.dat，与 prompts/ 明文模板比对一致（含新加的常驻 Agent 语义）。
import os, subprocess, tempfile, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DAT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'shabao_ai', 'ai_prompt.dat')
PROMPTS = os.path.join(ROOT, 'prompts')
BT = os.path.join(ROOT, 'build', 'buildtools')

# clean/build 会删除 build/buildtools；校验脚本自行准备依赖，避免调用顺序影响结果。
build_tools_class = os.path.join(BT, 'BuildTools.class')
if not os.path.exists(build_tools_class):
    os.makedirs(BT, exist_ok=True)
    subprocess.run([
        'javac', '-encoding', 'UTF-8', '-d', BT,
        os.path.join(ROOT, 'tools', 'BuildTools.java')
    ], check=True)

entries = {}
for line in open(DAT, encoding='utf-8'):
    line = line.rstrip('\n')
    if line and '=' in line:
        k, _, c = line.partition('=')
        entries[k.strip()] = c.strip()

tmpdir = tempfile.mkdtemp()
runner = os.path.join(tmpdir, 'V.java')
open(runner, 'w', encoding='utf-8').write('''
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public final class V {
    public static void main(String[] a) throws Exception {
        Method de = BuildTools.class.getDeclaredMethod("de", String.class);
        de.setAccessible(true);
        for (int i = 0; i < a.length; i += 2)
            Files.writeString(Path.of(a[i + 1]), (String) de.invoke(null, a[i]), StandardCharsets.UTF_8);
    }
}
''')
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', BT, '-d', tmpdir, runner], check=True)
args = []
for k in entries:
    args += [entries[k], os.path.join(tmpdir, k + '.txt')]
subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', tmpdir + ';' + BT, 'V'] + args, check=True)

# 逐条与明文模板比对
ok = True
for k, fname in [('PROMPT', 'prompt.txt'), ('EFFORT_LOW', 'effort_low.txt'), ('EFFORT_MED', 'effort_med.txt'),
                 ('EFFORT_MAX', 'effort_max.txt'), ('PROMPT_EXAMPLE', 'prompt_example.txt')]:
    dec = open(os.path.join(tmpdir, k + '.txt'), encoding='utf-8').read()
    src = open(os.path.join(PROMPTS, fname), encoding='utf-8').read()
    if dec != src:
        print('MISMATCH %s' % k)
        ok = False
    else:
        print('一致 %s (%d chars)' % (k, len(dec)))

if not ok:
    sys.exit('解密与明文模板不一致')
prompt = open(os.path.join(tmpdir, 'PROMPT.txt'), encoding='utf-8').read()
assert '常驻 Agent 模式（v22·Runtime）' in prompt, '缺少常驻Agent语义'
assert 'set_goal' in prompt and 'wait_until' in prompt and 'speak' in prompt, '缺少新工具说明'
assert 'relationship' in prompt and 'pursue_partner' in prompt and 'accept_partner' in prompt, '缺少关系工具说明'
assert 'social_interaction' in prompt and 'hold_hand' in prompt and 'kiss' in prompt and 'cancel_interaction' in prompt, '缺少身体互动工具说明'
assert '相同回合重复调用不会增加进度' in prompt and '不得在同一回合反复调用刷进度' in prompt, '缺少多回合关系门槛'
assert '【伴侣暧昧表达】' in prompt and '不写露骨的性行为或器官细节' in prompt and '一次“可以”' in prompt, '缺少伴侣暧昧边界'
assert '【方向真值表】' in prompt, '缺少自检锚点'
assert '%s' in prompt and '%d' in prompt, '缺少占位符'

# 动态角色名不写进密文模板，而由 Java 在每轮按 ModConfig 安全注入；同时锁定核心身份。
executor = open(os.path.join(ROOT, 'src', 'main', 'java', 'cn', 'shabaoai', 'companion', 'ai',
                             'AgentExecutor.java'), encoding='utf-8').read()
llm_client = open(os.path.join(ROOT, 'src', 'main', 'java', 'cn', 'shabaoai', 'companion', 'ai',
                               'LlmClient.java'), encoding='utf-8').read()
config = open(os.path.join(ROOT, 'src', 'main', 'java', 'cn', 'shabaoai', 'companion', 'config',
                           'ModConfig.java'), encoding='utf-8').read()
interaction_manager = open(os.path.join(ROOT, 'src', 'main', 'java', 'cn', 'shabaoai', 'companion', 'ai',
                                        'InteractionManager.java'), encoding='utf-8').read()
guard_core = open(os.path.join(ROOT, 'src', 'main', 'java', 'cn', 'shabaoai', 'companion', 'protect',
                               'GuardCore.java'), encoding='utf-8').read()
agent_action = open(os.path.join(ROOT, 'src', 'main', 'java', 'cn', 'shabaoai', 'companion', 'ai',
                               'AgentAction.java'), encoding='utf-8').read()
for source in (executor, llm_client):
    assert '你本质上始终是 shabao' in source and 'txcxgzs(ban)' in source, '模型入口缺少固定核心身份'
    assert 'configuredName' in source and 'toJson(configuredName)' in source, '模型入口缺少安全的动态名字注入'
assert 'companionEntityName' in config and 'CORE_IDENTITY = "shabao"' in config, '实体名牌缺少 shabao 核心标识'
assert 'DEFAULT_COMPANION_NAME.equals(displayName)' in config, '默认名称沙包不应附加 (shabao) 后缀'
assert 'state.foregroundTurnId()' in executor and 'UUID.randomUUID().toString()' in executor, '前台关系工具没有使用真实回合 ID'
assert 'executeRelationship(player, action, null)' in executor and 'executeSocialInteraction(player, action, false)' in executor, '后台关系/互动来源没有锁为非玩家发起'
assert 'cancel_interaction' in executor and 'cancel_interaction' in agent_action, '通用取消互动工具未贯通 schema/解析层'
assert 'if (s.phase == Phase.APPROACH)' in interaction_manager and '32.0 * 32.0' in interaction_manager, 'APPROACH 距离规则未按阶段拆分'
assert 'new Vec3d(-forward.z, 0, forward.x)' in interaction_manager, '牵手左右方向向量错误'
assert 'HOLDING_DURATION_TICKS = 100' in interaction_manager and 'HOLD_SIDE_OFFSET = 0.62' in interaction_manager, '牵手时长或距离参数回归'
assert 'IntegrityState.UNVERIFIED' in guard_core and 'STATE.get() != IntegrityState.VERIFIED' in guard_core, '完整性状态未默认拒绝'
assert 'fail("完整性清单 obf_manifest.dat 缺失")' in guard_core, '清单缺失仍可能 fail-open'
print('PROMPT 内容检查 PASS：常驻Agent语义 + 关系/互动工具 + 动态角色名 + 固定核心身份 + 锚点 均存在')
