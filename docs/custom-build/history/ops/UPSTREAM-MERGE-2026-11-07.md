# ops-v2 合并上游（2026-11-07）

## 计划与边界

用户要求合并 RikkaHub 上游与 opsv2；实际分支名为 `ops-v2`。

1. 核实现场：coding `/mnt/coding/rikkahub-build/src/rikkahub`，ops-v2 HEAD `a4341901`，基线 `903e058b`。唯一未提交改动为 Gradle wrapper 国内镜像；ssh-p0 是独立 worktree，不合并、不修改。
2. 拉取 origin/master，核对上游增量与与 ops-v2 重叠文件。保存旧 HEAD 的 archive 分支与 wrapper 补丁。
3. 采用 merge（保留已有四个 ops-v2 提交，不重写历史），仅将 origin/master 合进 ops-v2。冲突逐项审阅，保留原生分页、不可变 ref、Markdown 512KiB/历史豁免、最终投影与 .ops debug 包名。versionName 跟上游，不关闭官方更新提醒。
4. 恢复本机构建镜像差异，导出合并后补丁；执行真实 Kotlin/JVM 回归以及 Gradle debug/release 构建，长任务后台运行，禁止 sleep/循环轮询。
5. 记录新基线、HEAD、冲突、验证结果、日志与回退方式。未验证之处明确标注；不自动安装，不推送上游。

不改宿主/CT 内存、swap、CPU、磁盘、网络配置；不动 ssh-p0 分支和手机数据。

## 现场核对

- 2026-11-07：ops-v2=a4341901；origin/master（尚未 fetch）=903e058b；工作树只有 `gradle/wrapper/gradle-wrapper.properties` 改动。
- coding 无活动 Gradle 构建，/mnt/coding 剩余约 6.8T。

## 实施与当前验证状态

- 上游 `fe5a28f9a25fb6267065881d7738dd909ad9e51e`，versionName 3.1.2。相对旧基线25个提交。
- merge提交 `3adc2271d3f8a5f745ee5015d8fef012942ca825`，父提交为原ops-v2 `a4341901` 与上游 `fe5a28f9`。Git没有文本冲突；人工审阅发现并消除ChatToolFactory形参/ChatRequestFactory调用参数中的conversationId重复声明。
- 新后台shell返回taskId/status/logPath/output，与旧同步shell的exitCode/stdout/stderr不同：补充v2适配，避免running误报error；workspace_shell_output/kill也纳入历史预算。视图保留任务标识、日志位置、退出码；skippedBytes>0明确标记source_truncated，不宣称ref能恢复源端丢失字节。
- 新增4项回归，原37项保留；构建前真实Kotlin/JVM **41项全部通过（1.118s）**。该轮使用既有已编译Android模块类型，不能冒充上游新模块完整编译通过。
- 已启动debug/release全量Gradle构建（含web，未跳过），构建完成后脚本自动再跑新模块类型的41项测试。当前仅确认后台任务正常运行；无构建成功结论。
- 日志 `/mnt/coding/rikkahub-build/logs/ops-v2-upstream-20261107.log`；真实脚本PID4768（启动外层4767）；结束标记 `OPS_V2_UPSTREAM_BUILD_EXIT=<rc>`。脚本本地 `merge-20261107/validate-and-build.sh`，远端同名。启动SSH因外层后台shell继承句柄超时，已仅做一次诊断，确认41项通过及Gradle活动；未重启重复构建，不再轮询。
- 工作树仅保留原有wrapper镜像环境差异；ssh-p0未修改。没有主机/CT配置改动，没有自动安装/推送。
- 当前备份 `merge-20261107/DELTA.diff`：对上游fe5a28f9的完整二进制diff，可在同基线`git apply --index`恢复；bundle保存merge历史，需上游包含903e058b与fe5a28f9两项前提，`git bundle verify`已通过。
- 旧 `patches-v2/ops-v2.mbox` 保留为3.1.1历史，不代表本次merge；不得用format-patch默认遗漏merge提交的输出冒充完整恢复补丁。
- 下次用户询问只查一次日志；成功后核验APK新版本、包名、签名与SHA256，再取回产物。不自动安装。

## 用户追问进度（同日）

已单次查日志：41项构建前回归通过，Gradle仍处于app配置阶段，未出现BUILD SUCCESSFUL或结束标记，wrapper PID4881、daemon PID4921仍在运行。无源码编译报错，只有srcDirs弃用警告。进一步线程转储确认主构建线程正在Gradle依赖下载的HTTPS响应体读取（DownloadAction→ContentLengthInputStream→SSL socket read），而不是Kotlin编译/测试死锁；此证据不足以确认具体依赖或断定永久卡死。诊断快照 `merge-20261107/gradle-config-thread-dump.txt`。没有反复查询、重启构建或改参数；新版release未完成，因此未下载旧APK冒充新包，构建后41项回归与APK核验/取回仍待完成。

## 最终构建、测试与取包（用户再次check）

- 单次查日志确认 `BUILD SUCCESSFUL in 12m 36s`、799项任务（176执行、623 up-to-date），debug/release及web全量构建完成，`OPS_V2_UPSTREAM_BUILD_EXIT=0`，无活动构建任务。
- 构建后以新编译Android模块类型执行真实Kotlin/JVM：`OK (41 tests)`，1.002s。构建前41项也通过；不能将其称为Android实机验收。
- 核验arm64 release：`me.rerere.rikkahub` / versionName3.1.2 / versionCode195 / arm64-v8a；APK v2签名校验通过，证书SHA256 `35448b679c3366f6e277156079fae32cbd4c60661b4126c1db55220059448201`，沿用原自编译release签名。
- ZIP CRC全部正常；含`ops-v2/query.sh`；DEX含workspace_result/shell_output/shell_kill、skippedBytes、source_truncated、paged、snapshot、taskId=等标记。此项仅为静态打包smoke。
- 取回文件 `artifacts/rikkahub-v2-3.1.2-3adc2271-arm64-release.apk`，16,271,635字节；本地与远端SHA256均为 `716c6109be35f76c909a91e9507916cf5b5f19b431f6119e23343eed2635425d`。
- 来源HEAD仍为3adc2271；工作树仅wrapper镜像环境差异。未自动安装、未更改机器配置。此前下载等待已自行结束，无须调参。

## 回退

保留 `archive/ops-v2-before-upstream-20261107` 指向合并前 HEAD；wrapper 环境差异单独备份。若需回退，在确认没有新修改后恢复该备份分支/提交，而不是碰 ssh-p0 或删除工作区。
