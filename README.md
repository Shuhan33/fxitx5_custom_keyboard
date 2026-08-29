# slei 键盘

这是基于 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 的个人定制输入法。
本仓库是 slei 键盘自己的主仓库，GitHub 地址是：

<https://github.com/Shuhan33/fxitx5_custom_keyboard>

当前发布版本：**1.0.0**

应用包名：`org.fcitx5.slei.android.fx`

许可证：LGPL-2.1-or-later

---

## 第一部分：给人读的

### 主要改动

- 应用发布名称统一为“slei 键盘”，减少上游 `.fx` 名称与定制版本之间的混淆。
- 拼音候选支持保守的常用英文补全，例如输入 `fathe` 或 `father` 时可以看到 `father`；`uohx` 这类未命中英文词典的双拼串不会作为英文候选占位。
- 英文联想开关位于“设置 → 键盘 → 英文联想”，默认开启。
- 中文键盘底栏不再放“英”快捷开关；自定义布局若添加该功能，显示为 `ABC`。
- 候选栏支持连续左右滑动，横向最多展示前 10 个候选；只要存在候选，最右侧下拉按钮就始终可用，展开后从第 1 个候选开始显示完整列表。
- 符号页改为真正的横向连续滚动，可以停在两列之间，不是整页切换。
- 数字键盘的九宫格横向收窄；主键盘、数字键盘和符号键盘共用同一高度，竖屏默认 27%。
- 中文键盘的 `V` 键上滑输入下划线 `_`。
- 检测到新复制文本后，下一次输入会在候选区显示可关闭的快捷粘贴卡片；密码输入框不会显示。完整剪贴板支持编辑、删除、置顶和再次使用排序。
- 中文排序使用 APK 内置的 libime 词典与语言模型，离线可用；本地输入历史按次数以非线性权重参与排序，并可在高级设置调整上限。
- 保留 Apple 风格的默认主题、键盘布局和相关视觉调整。

### 安装包位置

每次发布的 APK 都放在开发机的：

```text
D:\Lei\Documents\slei-keyboard-apk
```

文件名包含应用、版本和 ABI，例如：

```text
org.fcitx5.slei.android.fx-1.0.0-arm64-release.apk
```

目前主要构建 `arm64-v8a`。安装前请确认手机允许安装来自文件管理器的 APK；更新安装时需要使用相同签名。

### 版本号规则

版本号遵循 `主版本.次版本.修订版本`：

- **修订版本**：修复崩溃、修复联想、减少卡顿、调整文案或间距，例如 `0.2.0 → 0.2.1`。
- **次版本**：新增一整块功能或改变一整块交互，但保持兼容，例如 `0.2.0 → 0.3.0`。
- **主版本**：包名、数据格式或主要使用方式不兼容，或准备作为稳定公开版本，例如 `0.x → 1.0.0`。

版本配置在 `build-logic/convention/src/main/kotlin/Versions.kt`。  
`arm64-v8a` 的 `versionCode` 计算方式是 `baseVersionCode * 10 + 2`；正式版 `1.0.0` 使用 `1002`。

### 1.0.0 发行说明

- 修复重复附加同一输入窗口时仍执行完整移除、布局和重挂载的问题。
- 候选栏仅在候选数据变化时更新展开状态，不再在每个滚动像素重复触发状态机。
- 清理缓存改为后台 I/O，避免设置页面被大目录遍历阻塞。
- 删除已经被连续 RecyclerView 取代的旧分页符号 UI 实现，降低维护成本和包内冗余。
- 数字区与两侧功能键的宽度差缩小；主键盘、数字键盘和符号键盘共用同一高度，竖屏默认值按实机体验设为 27%。
- 符号滚动保留 Android 原生惯性与任意位置停止，并减少首帧宽度跳变和无效刷新。
- 修复分页候选不足 10 项时显示词与实际上屏词可能不一致的问题；右侧展开按钮始终固定，完整候选从第 1 项开始。
- 修复拼音配置被写入错误 INI 节、历史权重与小鹤双拼设置可能静默失效的问题，并迁移旧配置。
- 英文候选仅保留词典支持的前缀补全，删除任意字母串原样进入候选的逻辑。
- 删除首次启动联网下载词库及失败后反复重试的行为，保证离线首次启动的一致性。
- 新增剪贴板快捷粘贴关闭按钮与敏感输入保护；剪贴板详情继续支持编辑、删除和置顶。
- 通过 Ubuntu `arm64-v8a` release 全量构建、R8、lintVital 和完整 lint 检查。

### Ubuntu 编译

Windows 宿主机不编译 native 部分，避免触发宿主机显卡/驱动问题。推荐在 Ubuntu 虚拟机中编译：

```bash
cd ~/src/fxitx5_custom_keyboard
git submodule update --init --recursive

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export BUILD_ABI=arm64-v8a
export GRADLE_OPTS=-Xmx3072m

./gradlew :app:assembleFxRelease
```

完整 lint（先完成一次 assemble，使 native 数据描述已经生成）：

```bash
./gradlew :app:lintFxRelease -x :app:generateDataDescriptor
```

该版本固定使用中文基础界面，因此 lint 仅关闭上游遗留的 `MissingTranslation` 检查；其余正确性检查保持开启。

需要的主要工具版本：

- JDK 17
- Android SDK / compileSdk 36
- Android NDK `28.0.13004108`
- CMake `3.31.6`
- `extra-cmake-modules`
- `gettext`

产物位于：

```text
app/build/outputs/apk/fx/release/
```

### 上游和贡献

核心代码和引擎来自 Fcitx5、fcitx5-android、libime 及相关项目。  
本仓库只维护 slei 定制版本；上游仓库是 `fxliang/fcitx5-android`，不要把 slei 的提交推送到上游。

---

## 第二部分：给 Agent 读的

以下内容用于帮助自动化 Agent 快速理解仓库约束、构建方式和修改边界。

### 仓库身份

- 本地工程：`D:\Lei\Documents\android_keyboard`
- GitHub 仓库：`Shuhan33/fxitx5_custom_keyboard`
- 默认维护分支：`main`
- 当前开发分支可能是 `apple-skin`
- GitHub remote `custom` 指向用户仓库
- Git remote `origin` 指向上游 `fxliang/fcitx5-android`
- **只允许向 `custom` 推送；未经明确要求禁止向 `origin` 推送**
- 提交作者统一使用：`Shuhan33 <lei.shuhan@hotmail.com>`
- 不修改全局 Git 配置；单次提交使用 `git -c user.name="Shuhan33" -c user.email="lei.shuhan@hotmail.com"` 或等效环境变量

### Android 变体和包名

- Kotlin / Java namespace：`org.fcitx.fcitx5.android`
- slei FX applicationId：`org.fcitx5.slei.android.fx`
- 默认发布任务：`:app:assembleFxRelease`
- ABI 环境变量：`BUILD_ABI=arm64-v8a`
- 发布 APK 复制到 `D:\Lei\Documents\slei-keyboard-apk`
- 不要把本地 APK、`local.properties`、签名文件或用户数据提交到 Git

### 代码区域

- 键盘布局与按键定义：`app/src/main/java/.../input/keyboard/`
- 拼音、英文联想和候选栏：`app/src/main/java/.../data/pinyin/`、`input/candidates/`
- 符号/表情选择器：`app/src/main/java/.../input/picker/`
- 输入法设置：`app/src/main/java/.../ui/main/settings/`
- 默认键盘 JSON：`app/src/main/res/raw/text_keyboard_layout.json`
- 用户布局迁移：`input/config/UserConfigFiles.kt`
- 拼音 native 源码：`lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/`
- libime native 源码：`lib/libime/src/main/cpp/libime/`

### 已知实现约定

- 符号页必须使用连续横向滚动，不要恢复 ViewPager 分页，也不要加入 `PagerSnapHelper`。
- 符号网格使用横向 `GridLayoutManager` 时，数据必须转换为列优先顺序，否则符号排列会错位。
- 候选横滑上限是 10；右侧展开按钮不随滚动位置消失，expanded candidate window 从 offset 0 显示包含前 10 个在内的完整列表。
- 英文联想开关的设置 key 是 `english_spell_candidates`，对应 pinyin 配置中的 `SpellEnabled`。
- 中文模式下英文候选必须来自内置英文词典并匹配输入前缀；不要重新加入“任意 Latin 字符串原样候选”。
- `pinyin.conf` 的 `SpellEnabled`、`HistoryWeightPercent`、`ShuangpinProfile` 等键必须写在配置根部，不能放入 `[PinyinEngine]` 节。
- 词频模型必须保持离线可用，不要在输入法启动路径加入网络词库下载或重试。
- 自定义布局中的英文联想按键文案是 `ABC`，默认中文键盘布局不包含该按键。
- 修改 pinyin/libime submodule 时，要同时维护父仓库中的补丁文件和 CMake 应用逻辑，避免只在本地 dirty submodule 生效。
- native 构建只在 Ubuntu VM 执行，不要在 Windows 上运行完整 Gradle native build。

### 提交和发布流程

1. 检查工作区，确认没有误删语言资源、密钥、用户配置或本地构建文件。
2. 修改代码并在 Ubuntu VM 使用 JDK 17 构建 `:app:assembleFxRelease`。
3. 验证 APK 版本名、ABI 和文件名。
4. 将 APK 复制到 `D:\Lei\Documents\slei-keyboard-apk`。
5. 使用作者 `Shuhan33 <lei.shuhan@hotmail.com>` 创建提交。
6. 只推送到用户仓库 `custom`；需要同步主线时，明确推送到 `custom/main`。
7. 不使用 force push，除非用户明确要求重写远程历史。

### 远程历史重写说明

如果用户明确要求统一历史作者，目标仅限 `custom` 仓库的分支。应先备份并确认 `custom/main`、`custom/apple-skin` 的远程状态，再用新的提交身份重写；不得重写或强推 `origin` 上游分支。
