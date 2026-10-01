# 上游跟随说明（Upstream Port Guide）

本分支 = 原版 `rikkahub/rikkahub` master + 少量本地改动。**默认尽量与上游保持一致**；
仅在确有需要时才附加本地改动，并在此记录，便于上游更新时对照处理冲突。

> 历史说明：本分支曾移植过「工作区 Android 本地互通」系列功能
> （`/sdcard` 全盘访问、SAF `/local` 本地目录镜像、工作区隔离开关），该系列已整体下线并还原为上游行为。
> 相关数据库列（`android_local_access` / `local_directory_uri` / `sdcard_subpath`）作为已停用遗留列保留，
> 仅用于兼容既有数据库；迁移链 `Migration_24_25/25_26/26_27` 与 `AppDatabase` 版本号 (27) 保持不变，
> 以保证既有用户可覆盖安装、不丢数据。

## 当前本地差异

| 文件 | 差异 | 处理方式 |
|------|------|---------|
| `app/build.gradle.kts` | `applicationId = "me.iwry.rikkahub"` | 保留本 fork 的应用身份，避免与官方版签名冲突（namespace 不可改）；上游改 defaultConfig 时保留此值 |
| `app/google-services.json` | 本 fork 独有文件 | 保持包名条目与 `applicationId` 一致（release/debug 两条） |
| `app/src/main/java/.../data/db/entity/WorkspaceEntity.kt` | 保留 3 个已停用遗留列 | 上游改该实体时保留这些列，或按需在实体/迁移中一并处理 |
| `app/src/main/java/.../data/repository/WorkspaceRepository.kt`、`WorkspaceTools.kt` 等 | 若干与上游并行的增强（如工具集） | 上游改动这些文件时逐处并入，避免误删本地增强 |

## 构建注意

- 原版要求 `app/google-services.json` 才能编译（Firebase）。CI 通过 Secret
  `GOOGLE_SERVICES_JSON`（文件原文）注入；本地开发从原项目获取后放到 `app/` 下。
- 单测：`./gradlew :workspace:testDebugUnitTest` 覆盖 Rootfs 路径解析等行为。

## 手动同步流程（无 CI 时）

```bash
git remote add upstream https://github.com/rikkahub/rikkahub.git
git fetch upstream master
git merge upstream/master        # 冲突按上表处理
git push
```
