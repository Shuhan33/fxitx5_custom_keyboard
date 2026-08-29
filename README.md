# slei 键盘

这是基于 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 的个人定制输入法。
本仓库是 slei 键盘自己的主仓库，GitHub 地址是：

<https://github.com/Shuhan33/fxitx5_custom_keyboard>

当前发布版本：**0.2.0**  
应用包名：`org.fcitx5.slei.android.fx`  
许可证：LGPL-2.1-or-later

---

## 第一部分：给人读的

### 主要改动

- 拼音候选支持英文联想，例如输入 `fathe` 或 `father` 时可以看到 `father`。
- 英文联想开关位于“设置 → 键盘 → 英文联想”，默认开启。
- 中文键盘底栏不再放“英”快捷开关；自定义布局若添加该功能，显示为 `ABC`。
- 候选栏支持连续左右滑动，横向最多展示前 10 个候选；更多候选通过下拉展开栏查看。
- 符号页改为真正的横向连续滚动，可以停在两列之间，不是整页切换。
- 数字键盘的九宫格横向收窄，数字键盘高度与字母键盘分开控制。
- 中文键盘的 `V` 键上滑输入下划线 `_`。
- 保留 Apple 风格的默认主题、键盘布局和相关视觉调整。

### 安装包位置

每次发布的 APK 都放在开发机的：

```text
D:\Lei\Documents\slei-keyboard-apk
```

文件名包含应用、版本和 ABI，例如：

```text
org.fcitx5.slei.android.fx-0.2.0-arm64-release.apk
```

目前主要构建 `arm64-v8a`。安装前请确认手机允许安装来自文件管理器的 APK；更新安装时需要使用相同签名。

### 版本号规则

版本号遵循 `主版本.次版本.修订版本`：

- **修订版本**：修复崩溃、修复联想、减少卡顿、调整文案或间距，例如 `0.2.0 → 0.2.1`。
- **次版本**：新增一整块功能或改变一整块交互，但保持兼容，例如 `0.2.0 → 0.3.0`。
- **主版本**：包名、数据格式或主要使用方式不兼容，或准备作为稳定公开版本，例如 `0.x → 1.0.0`。

版本配置在 `build-logic/convention/src/main/kotlin/Versions.kt`。  
`arm64-v8a` 的 `versionCode` 计算方式是 `baseVersionCode * 10 + 2`；例如 `0.2.0` 使用 `212`。

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
- 候选横滑上限是 10；第 11 个候选开始由 expanded candidate window 展示。
- 英文联想开关的设置 key 是 `english_spell_candidates`，对应 pinyin 配置中的 `SpellEnabled`。
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
