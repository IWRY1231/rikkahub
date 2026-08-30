# 工作区本地互通功能移植说明（Upstream Port Guide）

本分支 = 原版 `rikkahub/rikkahub` master + **6 个功能 commit**，为 Linux 工作区增加：

1. **所有文件访问（/sdcard）**：`MANAGE_EXTERNAL_STORAGE` 授权后，工作区 AI 可通过 `/sdcard` 读写手机全部文件；
2. **本地互通（/local）**：通过系统目录选择器（SAF）授权任意文件夹，双向同步挂载为 `/local`，无需全盘权限；
3. **工作区隔离开关**：每个工作区可单独关闭「Android 本地读写工作区与本地互通」。

## 功能 commit 列表（按顺序）

| # | Commit 主题 | 主要文件 |
|---|------------|---------|
| 1 | feat(workspace): 绕过PRoot的直接IO写入与Android本地挂载开关 | workspace 模块 4 文件 + 测试 |
| 2 | feat(app): 统一Android本地挂载表并支持「所有文件访问」 | WorkspaceMounts.kt(新)、Manifest、ContextUtil.kt、RepositoryModule.kt |
| 3 | feat(db): 工作区新增本地互通配置列 | WorkspaceEntity、WorkspaceDAO、Migration_24_25(新)、AppDatabase、DataSourceModule |
| 4 | feat(workspace): SAF本地目录双向同步挂载为/local | LocalDirectorySync.kt(新)、WorkspaceRepository |
| 5 | feat(ai): workspace工具支持/sdcard读写并在提示词中声明 | WorkspaceTools.kt、WorkspaceReminderTransformer.kt |
| 6 | feat(ui): 工作区详情页本地互通开关/全盘权限引导/SAF目录选择 | WorkspaceDetailPage/VM、TerminalSession(Manager)、AppModule、strings |

## 工作原理速记

- **挂载表唯一来源**：`WorkspaceMounts.androidLocalMounts(context)` 同时供 PRoot `-b` 参数
  （`buildBindMountArgs`）与文件工具路径解析（`WorkspaceManager.resolveRootfsPath`）使用，杜绝两处漂移；
- **直写而非 PRoot shell 写**：`workspace_write_file` 通过 `resolveRootfsPath` 映射到宿主机物理路径后
  直接 Java IO 写入（`writeRootfsText`），因此 `/sdcard` 这类 FUSE 挂载点可靠可写；
- **/local 镜像同步**：命令/文件操作前 `LocalDirectorySync.syncToMirror`（手机→镜像），
  操作后 `syncMirrorBack`（镜像→手机）；shell 命令只有命令文本包含 `/local` 时才同步，避免卡顿；
- **DB 迁移是防御式的**（`hasColumn` 检查），恢复旧备份不会 duplicate column 崩溃。

## 上游更新时的冲突处理

自动合并失败时 Actions 会开 Issue。90% 的冲突集中在这些位置，处理原则：

| 文件 | 冲突场景 | 处理方式 |
|------|---------|---------|
| `app/src/main/AndroidManifest.xml` | 上游增删权限 | 保留两边的权限行即可，无逻辑冲突 |
| `RepositoryModule.kt` | 上游改 WorkspaceManager 构造参数 | 保持 `bindMounts = WorkspaceMounts.androidLocalMounts(context)` 与 `WorkspaceRepository(get()x5)` |
| `DataSourceModule.kt` / `AppDatabase.kt` | **上游把 version 升到 25** | 把我们的两列合并进上游新迁移，或将 `Migration_24_25` 改名顺延（如 25→26）并保持 `hasColumn` 防御式写法；同时更新 Room schema |
| `workspace/WorkspaceManager.kt` | 上游改 `resolveRootfsPath`/`executeCommand` 签名 | 保留 `includeAndroidLocal`/`extraBindMounts` 参数与 `/local` 解析分支，再叠加上游逻辑 |
| `WorkspaceTools.kt` | 上游改工具描述/审批逻辑 | 保留 `WRITABLE_ROOT_PREFIXES` 三项与 `/sdcard` 描述行；确认 write 工具仍调用仓库层 `writeTextInRootfs`（直写） |
| `WorkspaceReminderTransformer.kt` | 上游改提示词 | 保留 `if (workspace.androidLocalAccess)` 两段注入 |
| `WorkspaceDetailPage.kt` | 上游改详情页布局 | 保留三块 UI（开关/权限引导/本地目录）与 VM 回调参数 |
| `WorkspaceTerminalSession(Manager).kt` | 上游改 proot 参数拼装 | 保留 `buildBindMountArgs(WorkspaceMounts...)` 分支与 `androidLocalAccess`/`localDirectoryUri` 参数 |

## 构建注意

- 原版要求 `app/google-services.json` 才能编译（Firebase）。CI 里通过 Secret
  `GOOGLE_SERVICES_JSON`（文件原文）注入；本地开发从原项目获取后放到 `app/` 下。
- 首次编译后 KSP 会在 `app/schemas/.../25.json` 生成新 schema，**记得提交**。
- 单测：`./gradlew :workspace:testDebugUnitTest` 覆盖直写/隔离/挂载裁剪行为。

## 手动同步流程（无 CI 时）

```bash
git remote add upstream https://github.com/rikkahub/rikkahub.git
git fetch upstream master
git merge upstream/master        # 冲突按上表处理
git push
```
