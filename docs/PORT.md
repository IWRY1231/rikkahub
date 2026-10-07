# 上游跟随说明（Upstream Port Guide）

本分支 = 原版 `rikkahub/rikkahub` master + 少量本地改动。**默认尽量与上游保持一致**；
仅在确有需要时才附加本地改动，并在此记录，便于上游更新时对照处理冲突。

> 历史说明：本分支曾移植过「工作区 Android 本地互通」系列功能
> （`/sdcard` 全盘访问、SAF `/local` 本地目录镜像、工作区隔离开关），该系列已整体下线并还原为上游行为。
> 相关数据库列（`android_local_access` / `local_directory_uri` / `sdcard_subpath`）作为已停用遗留列保留，
> 仅用于兼容既有数据库。

## 数据库版本与迁移链

- 当前 `AppDatabase` 版本号 **28**，迁移链为手写：`Migration_6_7 / 11_12 / 13_14 / 14_15 / 15_16 / 24_25 / 25_26 / 26_27 / 27_28`
  （见 `AppDatabaseFactory.create` 的 `addMigrations`），**不使用** 24→27 的 AutoMigration。
- 本 fork 的 v24→v27 与上游同名不同结构：本 fork 用于工作区扩展列，上游 v26 新增媒体创作三表、v27 新增会话 `config` 列。
  因此**不能直接沿用上游的 AutoMigration 与 `app/schemas/.../26.json、27.json`**。
- `Migration_27_28` 把上游这两次变更补齐到本 fork 的链上（`conversations.config` 列 + 三张 `media_creation_*` 表）。
- `app/schemas/.../27.json` 记录的是**本 fork** 的 v27 结构（工作区扩展列、无媒体创作表），用于覆盖安装时校验既有库；
  上游带来的 26/27.json 不作为本 fork 的 schema。
- 后续上游再升数据库版本时：继续在本 fork 链尾追加以 **上游版本号 +1** 命名的手写迁移，并同步更新 `version`。

## 当前本地差异

| 文件 | 差异 | 处理方式 |
|------|------|---------|
| `app/build.gradle.kts` | `applicationId = "me.iwry.rikkahub"` | 保留本 fork 的应用身份，避免与官方版签名冲突（namespace 不可改）；上游改 defaultConfig 时保留此值 |
| `app/google-services.json` | 本 fork 独有文件 | 保持包名条目与 `applicationId` 一致（release/debug 两条） |
| `app/src/main/java/.../data/db/entity/WorkspaceEntity.kt` | 保留 3 个已停用遗留列 | 上游改该实体时保留这些列，或按需在实体/迁移中一并处理 |
| `app/src/main/java/.../data/db/migrations/Migration_24_25/25_26/26_27/27_28.kt` | 本 fork 手写迁移 | 上游改 `AppDatabase`/schema 时按上节规则追加，勿并入上游 AutoMigration |
| `app/src/main/java/.../ui/pages/chat/ChatDrawer.kt` | 抽屉底部动作区：搜索/历史改为并排等宽按钮，媒体创作单独一行 | 上游改 `DrawerActions`/`DrawerEntry` 时保留该布局，把新入口按同样风格并入 |

### 已随上游一并采纳（无需再单独维护）

- 上游 `ui` 模块：通用 Compose 组件下沉到 `me.rerere.ui.*`，`settings.gradle.kts` 已 `include(":ui")`，`app` 依赖 `project(":ui")`。
- 上游删除助手字段 `allowConversationPromptInjection`（改为随会话固定），设置迁移 `PreferenceStoreV4Migration` 已并入迁移链。
- 上游新增的媒体创作（mediagen / media_creation_*）与「会话固定配置」（`ConversationConfig`、`conversations.config`）已并入。

## 构建注意

- 原版要求 `app/google-services.json` 才能编译（Firebase）。CI 通过 Secret
  `GOOGLE_SERVICES_JSON`（文件原文）注入；本地开发从原项目获取后放到 `app/` 下。
- `material3/material-color-utilities` 子模块需与上游记录的提交一致（`git submodule update --init --recursive`）。
- 单测：`./gradlew :workspace:testDebugUnitTest` 覆盖 Rootfs 路径解析等行为。

## 手动同步流程（无 CI 时）

```bash
git remote add upstream https://github.com/rikkahub/rikkahub.git   # 本仓库已配置时跳过
git fetch upstream master
git merge upstream/master        # 冲突按上表与「数据库版本与迁移链」处理
git push
```

> 合并前建议先打备份 tag（如 `git tag backup/pre-upstream-merge-<日期>`），DB 相关改动优先手动核对 schema 与迁移链。
