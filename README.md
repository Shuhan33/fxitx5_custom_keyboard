# slei 键盘

一款面向中文手机输入的离线 Android 输入法，基于
[Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 定制。

项目重点是：简洁的 Apple 风格界面、小鹤双拼、实用的候选栏与剪贴板、可调整的键盘尺寸，以及不依赖云端服务的本地词库和学习能力。

> 当前稳定版本：**1.1.0**<br>
> Android 包名：`org.fcitx5.slei.android.fx`<br>
> 主要发布架构：`arm64-v8a`

## 主要特性

- 中文界面，默认使用小鹤双拼。
- 中文键盘字母保持大写显示；英文输入遵循 Shift 和大小写状态。
- 候选栏可连续左右滑动，显示前 10 个候选；右侧始终保留完整候选展开按钮。
- 每次开始输入新的词语时，候选栏自动回到第一个候选。
- 中文模式支持常用英文补全，不会把无意义双拼串当作英文候选。
- 本地用户词频学习，可调整历史权重。
- Emoji、符号面板与连续横向滚动。
- 剪贴板支持快捷粘贴、搜索、编辑、删除和固定常用条目。
- 完全本地的输入统计：累计字数、每日字数、提交次数与常用词句排行。
- 统计数据可导入、导出和选择性清理，也可指定 SD 卡或系统文档目录作为导出位置。
- 内置输入法弹出耗时诊断，便于定位设备上的启动性能问题。
- 主键盘、数字键盘与符号键盘保持一致高度，竖屏默认约为屏幕高度的 27%。
- 跟随系统深色／浅色模式，使用简洁的圆角 Apple 风格主题。
- 拼音词库、用户历史和联想均可离线工作。

<img width="400" height="307.5" alt="image" src="https://github.com/user-attachments/assets/72c73e78-465f-4762-acc5-99ae315ba709" />


<img width="400" height="301" alt="image" src="https://github.com/user-attachments/assets/376fc404-f203-4c8d-a563-531ecde10247" />

## 键盘布局

默认中文全键盘：

- 第一行字母上滑输入数字 `1–0`。
- 第二行上滑输入 `~ @ # $ % & * ( )`。
- 第三行从 Shift 开始，`Z X C V B N M` 上滑输入 `' / - _ : ; \``。
- 第四行包含符号入口、逗号、空格、句号、语言切换和回车。
- 中文句号键显示并输入 `。`，长按可选择半角句号 `.`。
- `V` 上滑显示并输入下划线 `_`，不会转换成中文破折号。

键盘高度、字体、按键边距、主题和部分布局可以在应用设置中调整。

## 剪贴板

输入法可以在下一次输入时显示最近复制文本的快捷粘贴卡片。卡片可直接关闭，密码输入框不会显示该提示。

打开完整剪贴板后可以：

- 点击文本直接粘贴；
- 编辑或删除条目；
- 使用图钉固定地址、邮箱等常用内容；
- 按全部、固定、本地、媒体和远程来源分类查看。
- 在面板顶部搜索全部本地剪贴板记录。

固定条目不会被普通的数量限制或过期清理删除。

## 词库与隐私

slei 键盘不需要联网即可完成日常中文输入。用户词频、剪贴板数据库、输入统计和设置保存在本机；统计模块会跳过密码和敏感输入框。布局、字体和用户词库等文件使用 Android 应用专属外部目录。

可重建的临时缓存优先使用应用专属外部缓存，并按照容量和时间自动清理。卸载应用前如需保留用户数据，请先手动导出词库或备份。

## 安装

1. 从本仓库发布页或维护者提供的位置下载 `arm64` APK。
2. 在 Android 设置中允许文件管理器安装未知来源应用。
3. 安装后打开 slei 键盘，按引导启用输入法。
4. 在系统键盘列表中选择 slei 键盘。

从旧版本升级时需要使用相同签名。若系统提示签名不一致，请先备份词库和设置，再卸载旧包。

## 版本说明

### 1.1.0

- 缩短输入视图首帧热路径：剪贴板同步与语音提供方检查延后到键盘显示后执行。
- 增加输入法弹出、视图创建和服务启动耗时诊断。
- 增加完全本地的输入统计、常用词句排行、JSON 导入导出与分项清理。
- 增加可持久授权的外部备份目录，可选择 SD 卡或系统文档目录。
- 剪贴板增加实时搜索，保留编辑、删除、置顶及来源分组。
- 将纠音短语、个人短语和拼音词库入口集中到“数据与个性化”。
- 修正中文句号键帽显示；长按可直接输入半角 `.`。

### 1.0.2

- 修复选择后续候选词后，下一个词仍停留在候选栏后方的问题。
- 修复中文标点转换导致 `V` 上滑键帽把 `_` 显示为破折号的问题。
- 缩短按键放大预览高度，使按下反馈更紧凑。
- README 改为面向使用者的项目介绍。

### 1.0.1

- 剪贴板增加直接可见的固定入口。
- 增加保守的离线词组级误读纠正。
- 优化候选局部刷新、符号数据预计算和图片缓存。
- 临时文件优先使用应用专属外部缓存，并增加自动清理。

### 1.0.0

- 统一候选栏、符号滚动和键盘高度。
- 修复高级设置崩溃与拼音配置写入问题。
- 完成包名、中文界面、Apple 风格主题和 release 构建流程。

## 从源码构建

推荐使用 Ubuntu、JDK 17、Android SDK 36、NDK `28.0.13004108` 和 CMake `3.31.6`：

```bash
git clone --recursive https://github.com/Shuhan33/fxitx5_custom_keyboard.git
cd fxitx5_custom_keyboard

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export BUILD_ABI=arm64-v8a

./gradlew :app:assembleFxRelease
```

Gradle 会复用未变化模块的编译结果；首次构建或 native 配置发生变化时会明显更慢。APK 输出位于：

```text
app/build/outputs/apk/fx/release/
```

## 开源与上游

本项目保留 Fcitx5、Fcitx5 for Android、libime 及其他依赖项目的原作者信息和许可证。本仓库中的修改继续按照项目现有的 `LGPL-2.1-or-later` 许可证发布。

特别感谢 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的工作；该项目同样是本定制版的重要上游与实现参考。

- 定制仓库：<https://github.com/Shuhan33/fxitx5_custom_keyboard>
- Android 上游：<https://github.com/fcitx5-android/fcitx5-android>
- fxliang 上游：<https://github.com/fxliang/fcitx5-android>
- Fcitx5：<https://github.com/fcitx/fcitx5>

## 源码命名空间

slei 新增的独立模块统一位于 `fcitx5.slei`。Android 应用 ID 保持为 `org.fcitx5.slei.android.fx`，以保证已安装版本能够原地升级并保留数据。上游核心中的 `org.fcitx.fcitx5.android` 命名空间与 JNI、AIDL、插件协议存在硬绑定，因此保留原名；这也让后续同步上游修复更可靠，而不是进行容易破坏二进制兼容性的全局替换。

问题反馈请使用本仓库的 [Issues](https://github.com/Shuhan33/fxitx5_custom_keyboard/issues)。
