# 自动化维护说明

本文档用于自动化 Agent 和维护者，不属于面向最终用户的 README。

## 仓库与提交

- 用户仓库 remote：`custom`，目标为 `Shuhan33/fxitx5_custom_keyboard`。
- 上游 remote：`origin`，禁止把 slei 定制提交推送到上游。
- 默认维护分支：`main`。
- 提交身份：`Shuhan33 <lei.shuhan@hotmail.com>`。
- 只暂存当前任务修改的文件；保留未知或无关的 dirty submodule 状态。

## Android 构建

- applicationId：`org.fcitx5.slei.android.fx`。
- release 任务：`:app:assembleFxRelease`。
- 默认 ABI：`arm64-v8a`。
- native release 仅在 Ubuntu SSH 构建机运行，不在 Windows 主机编译。
- Ubuntu 构建目录：`/home/slei/build/fxitx5_custom_keyboard-1.0.0`。
- Windows APK 发布目录：`D:\Lei\Documents\slei-keyboard-apk`。

Gradle 增量构建目录和 native `.cxx` 目录应保留。不要运行 `clean`，除非缓存已损坏或构建配置确实要求全量重建。

## 产品约定

- 中文候选横栏最多显示前 10 项，右侧展开按钮始终存在，展开列表从第一个候选开始。
- 新候选内容到达时横栏回到位置 0；仅移动候选高亮时不要强制复位。
- 中文模式英文候选只能来自词典前缀匹配，不允许任意拉丁串占位。
- `V` 上滑必须显示并提交字面下划线 `_`，不得经过中文标点映射。
- 符号面板使用连续横向 RecyclerView，不加入分页吸附。
- 主键盘、数字键盘和符号键盘默认高度保持一致，竖屏为 27%。
- 核心设置、历史和剪贴板数据库保存在内部存储；只有可重建缓存和适合外置的大文件使用应用专属外部存储。

## 发布检查

1. `git diff --check`。
2. Ubuntu 构建 `:app:assembleFxRelease`。
3. 使用 `aapt2 dump badging` 核对包名、versionName 和 versionCode。
4. 计算 SHA-256，并复制 APK 到发布目录。
5. 以指定身份提交，只推送 `custom/main`。
