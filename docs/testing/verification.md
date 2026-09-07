# 验证与门禁

改动怎么验收：本地三层（秒级、无设备）→ 模拟器收敛 → 真机终验 →
发布产物本身冒烟。本文只讲方法与门禁构成；不保留历史结果——任何
一轮的通过记录都不能代替当前产物的验收。

需求口径见 [../product/requirements.md](../product/requirements.md)，
设计语义见 [../design/keyboard.md](../design/keyboard.md)。

## 0. 原则

- **mock 优先**：UI 逻辑与桥接行为先在 `mock_bridge_tests.js`
  （fake DOM + fake Native，node 直跑）验证；Kotlin 纯函数用 JVM 单测
  钉住；设备只做端到端回归。
- **断言跟行为走**：改可见语义先 grep `scripts/verify/` 里的字形字面量
  与元素 id，级联更新套件后再发版。
- **设备断言以宿主编辑器文本为 oracle**（uiautomator dump 的 text 属性
  与截图），不信键盘自身的乐观 UI 状态。
- **触摸必须真实**：能被页面合成事件骗过的路径（滚动、手势、浮层）用
  adb input / CDP `Input.dispatchTouchEvent` 驱动；DevTools 只读 DOM、
  几何与状态。
- **失败即重跑该组**：任何 FAIL → 修复 → 该套件全部用例重跑；部分
  通过不算收敛。
- **发布冒烟针对将发布的 APK 本身**：覆盖安装与真正首启两条路径都要
  在发布字节上跑过（`device_upgrade_verify.py` /
  `device_firstlaunch_verify.py`），且装机 SHA 与发布产物一致。
- **环境全部注入**：仓库内没有硬编码的设备序列号/主机名/路径；一次性
  探针放 `scripts/verify/check_*.py`，同样只读环境变量。

## 1. 本地门禁（无设备）

```bash
node scripts/verify/css_lint.js              # CSS 静态规则（R1-R3）
python3 scripts/generate-keyboard-data.py --check    # 键位图与 schema 一致
python3 scripts/generate-phrase-initials.py --check  # 常用语默认输入码表
node scripts/verify/mock_bridge_tests.js     # 键盘桥接功能套件
node scripts/verify/mock_settings_tests.js   # 设置页调用面
bash scripts/verify/mock_baseline_tests.sh   # 旧代键盘基线钉数（漂移即红）
ANDROID_HOME=… ./gradlew testDebugUnitTest   # 应用 JVM 套件（direct/play 两 flavor）
./gradlew --settings-file spikes/native-engine-smoke/settings.gradle.kts \
    --project-dir spikes/native-engine-smoke testDebugUnitTest   # 引擎冒烟 JVM
```

- **css_lint**（零依赖 node）：R1 `touch-action:none` 只允许键位网格，
  滚动容器子元素必须 pan-x；R2 初始/动态 hidden 的 id 若有无条件
  display 声明必须有 `#id[hidden]{display:none}` 抵消；R3 mock 套件的
  滚动容器必须在 CSS 有对应 `overflow-x:auto`（防改名后 mock 假绿）。
- **mock 基线**：`test-fixtures/keyboard-<旧版本>/` 内是与发布 APK 内嵌
  逐字节一致的键盘源码，套件按 `{since, until}` 键盘版本门控双代
  同跑，钉精确的 passed/failed/skipped 计数——harness 变更导致旧形态
  用例腐烂时在这里暴露。
- **滚动模型**：mock harness 对滚动容器有 clientWidth/scrollWidth/
  scrollLeft 模型与 `world.drag()`（preventDefault 即 cancelled）——
  「拖到底弹回最左」「横滑被 touchstart 杀掉」这类缺陷在本地可复现。

## 2. 设备门禁

前置环境变量（`run-all.sh` 第一步会核对装机 APK 与本地字节一致）：

```bash
export FEELIME_ADB_SERIAL=<serial>          # 测试设备
export FEELIME_VERIFY_APK=$PWD/app/build/outputs/apk/direct/debug/app-direct-debug.apk
export FEELIME_ASR_FIXTURE=<16kHz wav>      # 本地自备，不入库
export FEELIME_AAPT2=/path/to/aapt2         # 可选（x86_64 主机构建）
export FEELIME_BUILDER_SSH=<user@host>      # 可选（远端 x86_64 构建机跑 JVM 门禁）
export FEELIME_BUILDER_DIR=<远端仓库路径>    # 可选，默认 ~/code/feelime
export FEELIME_BUILDER_SDK=<远端 SDK 路径>   # 可选，默认 /opt/android-sdk
bash scripts/verify/run-all.sh             # 全量门禁
```

单跑某套件：`FEELIME_ADB_SERIAL=<serial> python3 scripts/verify/device_<name>_verify.py`
（环境变量见各脚本头部注释）。

### run-all 步骤

| 步骤 | 内容 |
| --- | --- |
| 1 | css lint + 两个生成器 `--check` |
| 2/2b | mock 桥接 / mock 设置页 + 旧代基线 |
| 3/4 | 应用 JVM + 引擎冒烟 JVM |
| 5 | `device_verify`：基础输入、中文全拼/双拼候选、模式持久化 |
| 6 | `device_gesture_verify`：真实手势（连删、上滑、长按弹层、录音浮层、光标滑动） |
| 7 | `device_extended_verify`：扩展语言/UI（法/俄/日、符号、候选翻页、主题） |
| 8 | `device_editor_verify`：宿主编辑器（多行、密码框、imeOptions、日语转换） |
| 9 | `device_panel_verify`：剪贴板/常用语面板与敏感编辑器行为 |
| 9a–9m | 各交互专项回归（见 §3 套件清单） |
| 10 | `device_resource_verify`：高度/资源预算（APK 体积、数据目录、PSS 增量） |
| 11 | ASR 五跑回归（见下） |

### ASR 门禁

`scripts/research/run-asr-regression.sh`（经 run-all 第 11 步）对冻结的
16 kHz WAV 跑五轮识别：识别结果必须包含关键 token；性能门限与
`scripts/research/asr-baseline.json`（**物理设备**上录制的基线）比较。
注意：

- x86_64 模拟器比物理设备慢一个量级，**跨设备类别的性能比较不可复现**：
  AVD 上自动 `--waive-perf-gate`（保留五跑 + 关键词检查），真机严格
  门限。
- 设备热状态直接决定性能结果（外壳温热即可使推理慢数倍）；性能门限
  必须在冷设备上跑。
- 该步的隔离 harness 会覆盖安装设备上的主应用，跑完必须重装主 debug
  APK 再做任何面板/编辑器类操作。

## 3. 套件清单

| 套件 | 覆盖点 |
| --- | --- |
| `device_verify` | 基础输入/布局/持久化（英文、全拼、双拼候选与确认、模式记忆） |
| `device_gesture_verify` | 退格连删、上滑/下滑、长按弹层与拖远取消、光标滑动、录音浮层 |
| `device_extended_verify` | 法/俄/日、符号层、候选翻页与展开区真实滑动、主题切换 |
| `device_editor_verify` | 多行 Enter、密码框、imeOptions、日语转换、宿主动作 |
| `device_panel_verify` | 剪贴板显示/粘贴/删除、常用语增删、敏感编辑器置空 |
| `device_caps_flick` | 大写锁定隔离、中文 flick、标点墨迹居中、变体列存活、快捷切换 |
| `device_settings_phrases` | 设置子页、常用语 CRUD、组合落地、preedit 不上屏 |
| `device_keymap` | 自然码键位图、设置二级页、候选条横滑 |
| `device_candidate_pool` | 统一候选池收起保真、全角 flick、编辑卡、行菜单 |
| `device_control_layer` | 控制键层只换工具条、粘滞组合 keyEvent、横屏、高度桥 |
| `device_control_switch` | 控制层开关语义、槽位不压缩键盘、高度卡 |
| `device_backspace_delete` | 退格左滑清组合、删词（真实 librime）、组合浮层角标 |
| `device_candidate_delete` | 任意候选删除、meta 位线上形态、横屏四行、语音设置页 |
| `device_fn_custom` | Fn 键码、扩展带浮层、定制 JSON 全链路、语音界面、重弹回主界面 |
| `device_height_card` | 高度卡/编辑卡真实触摸、设置页导航、界面语言 |
| `device_phrase_codes` | 常用语自动输入码、法语卡内编辑、光标快慢一致性 |
| `device_editor_modes` | 编辑器级模式限制、全拼展开、触摸状态清理、收起重开 |
| `device_replay_geometry` | 稳定展开列表、快捷切换目标、Fn 键面、横屏槽位预算 |
| `device_resource_verify` | 高度顶沿、APK/数据/PSS 预算 |
| `device_upgrade_verify` / `device_firstlaunch_verify` | 发布冒烟：覆盖安装 / 真正首启 |
| `device_model_import_verify` | 模型本地导入（SAF 选择器 → 校验 → 安装 → 麦克风） |
| `device_asr_production_verify` | 生产录音链路（模拟器音频注入 → 真实 InputConnection） |
| `check_*.py` | 一次性探针（诊断用，不进 run-all） |

新增用户可见行为时：先补 mock/JVM 用例，再加对应设备套件用例，并把
新套件接进 `run-all.sh` 的步骤序列。

## 4. 平台差异与已知怪癖

以「SKIPPED/放宽预算并写明原因」处理，真机保持严格断言：

- 模拟器（SwiftShader/ART 常驻开销）PSS 增量预算放宽；WebView 冷启动
  慢，几何/面板用例放宽轮询窗口。
- 部分 OEM 的密码输入框被系统安全键盘整体接管，IME 收不到 EditorInfo：
  设备断言记「平台接管」，敏感判定矩阵由 JVM `InputSensitivityTest`
  覆盖。
- WebView 收起后 DevTools 旧 target 可能僵尸：连接要按当前屏宽过滤、
  带超时重握手；旋转后可能残留多方向僵尸 target，不能按 pid 缓存命中。
- `dumpsys` 判定旋转成功要看实际字段（`mCurrentRotation`），uiautomator
  层级里的 rotation 属性优先于 SurfaceOrientation（部分设备横竖屏都可能
  缺失）。
- force-stop 当前默认 IME 可能触发系统切走默认输入法：套件开头重新
  `ime enable + set`；卸载重装后 RECORD_AUDIO 需重新授权（部分 OEM 拒绝
  adb 授权，走系统向导）。
- 引擎数据部署是异步的：全新安装后等 `.ready` 出现再跑套件。

## 5. 记录口径

验证记录绑定：被测产物（APK/键盘包 SHA-256）、运行环境（设备/模拟器）、
各套件通过计数、失败定性（功能回归 vs 环境怪癖）。修复后重跑的范围与
原失败范围一致；「上一轮该包通过过」不成立——换了字节就重新验。
