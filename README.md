# slei 键盘

基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的个人定制输入法。本仓库即主线（`main`），不是上游 `.fx` 发行说明。

- 包名：`org.fcitx5.slei.android.fx`
- 当前版本：`0.2.0`
- GitHub：https://github.com/Shuhan33/fxitx5_custom_keyboard
- 许可：LGPL-2.1-or-later（与上游相同）

## 这版做了什么

拼音输入、候选栏、符号页和数字键盘按日常使用改过：

- 符号页是横向连续滚动（可以停在两列中间），不是整页翻页
- 候选栏只横滑前 10 个，其余走下拉展开栏
- 拼音输入时可以出英文词（例如 `father`）；开关在设置里，默认打开
- 数字九宫格略收窄；数字键盘高度独立于字母键盘
- 中文 `V` 上滑是下划线 `_`

安装包每次打完会拷到本机 `Documents/slei-keyboard-apk`，文件名带版本号。

## 版本号

`build-logic/convention/src/main/kotlin/Versions.kt` 里的 `baseVersionName` / `baseVersionCode`。

| 变动 | 怎么加 |
| --- | --- |
| 修卡顿、修联想、改文案、调间距 | 补丁 `0.2.0` → `0.2.1` |
| 一整块交互或功能（本轮这种） | 次版本 `0.2.0` → `0.3.0` |
| 包名/数据不兼容，或对外稳定版 | 主版本 `1.0.0` |

arm64 的 `versionCode` = `baseVersionCode * 10 + 2`，例如 `0.2.0` 是 `212`。

## 在 Ubuntu 上编译

不要在 Windows 宿主机上编 native。在 Ubuntu 虚拟机里：

```bash
cd ~/src/fxitx5_custom_keyboard
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export BUILD_ABI=arm64-v8a
export GRADLE_OPTS=-Xmx3072m
./gradlew :app:assembleFxRelease
```

产物：`app/build/outputs/apk/fx/release/org.fcitx5.slei.android.fx-<version>-arm64-release.apk`

需要 JDK 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`、extra-cmake-modules、gettext。Submodule 要 `git submodule update --init --recursive`。

## 设置里和键盘上

- **英文联想**：设置 → 键盘 →「英文联想」。键盘底栏不再放这个开关；若自定义布局里加了该键，标签是 `ABC` 而不是「英」。
- 拼音方案等引擎选项仍在输入法设置里。

## 上游

核心引擎来自 Fcitx5 / fcitx5-android。本仓库只维护 slei 定制，请不要把这里的提交推到 `fxliang/fcitx5-android`。
