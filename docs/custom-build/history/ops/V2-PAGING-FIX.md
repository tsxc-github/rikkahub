# v2 装机反馈修复计划与记录

用户已批准按 V2-DEVICE-REVIEW.md 修改。本次只改 ops-v2，不动主机/CT配置、不迁移工作区、不自动安装手机包。

## 修复契约

1. 应用生成的自分页结果携带 `paged=true` 元数据；投影不再对其做首屏二次截断。兼容旧v2消息：已标记ops_v2的read/result按工具身份识别为分页输出。
2. 最新自分页页作为完整原子结果保留；先为它预留聚合正文预算，再分配普通历史。最新主动恢复的result页不被去重替换，避免再次要求读取ref。Markdown512KiB及历史豁免不变。
3. result行号明确为snapshot line，文件窗口为source line；不编造快照到源文件的行号映射。
4. 普通路径显示path:line:text；含冒号/空白/控制字符等的路径使用明确字段和JSON转义，NUL内部帧不变。

## 验证顺序

先加失败测试：真实页渲染→managed元数据→最终投影→提取模型可见cursor→续读；覆盖20KiB长行、满页多行、真实result工具、重复恢复及历史预算压力。再修实现并运行原28项与新组合测试，随后提交并后台构建debug包。

现场基线已核实：远端ops-v2 HEAD=2e105a41，只有原有Gradle wrapper国内镜像地址未提交。上一版debug构建日志为BUILD SUCCESSFUL，OPS_V2_BUILD_EXIT=0，与用户已装机反馈一致。

## 状态

- 已提交：`a4341901 ops-v2: keep self-paged output atomic through final projection; regress native ref restores`。
- 原代码先执行新增组合回归：35项中7项失败，确认能抓到真实缺陷；修复后与新增路径显示测试共 **37项全部通过**。
- 测试为真实Kotlin/JVM源码，加载真实已编译ai/app/workspace类型，并调用 `createV2ResultTool().execute()`；不是Python镜像。涵盖最终可见cursor续读、完整正文拼接、旧v2元数据兼容、重复ref恢复、预算压力、source/snapshot行号、特殊路径转义等。
- 测试日志：`/mnt/coding/rikkahub-build/ops-v2-verification/paging-before.log`、`paging-after.log`。测试脚本：本项目 `verify-v2-core.py`（远端同名）。
- 补丁已更新：`patches-v2/ops-v2.mbox`，当前4个提交。
- 聚合预算先为最新普通自分页页预留容量，剩余空间阶梯折叠历史；最新ref恢复不再用去重回执替换。没有取消普通历史预算，也没有缩小Markdown例外。

## 新构建（已成功并核验）

- 日志：`BUILD SUCCESSFUL in 4m 32s`，`OPS_V2_PAGING_BUILD_EXIT=0`，debug/release全部完成。
- arm64 release 已取回：`artifacts/rikkahub-v2-a4341901-arm64-release.apk`，15965401字节。
- SHA256：`9b46fdaf2ac8bc8c274db47a6672ccf584ac57c3264fc26f9bfdada3040b4f84`（远端与取回本地一致）。
- release包名 `me.rerere.rikkahub`，versionName 3.1.1 / code194；证书SHA256 `35448b679c3366f6e277156079fae32cbd4c60661b4126c1db55220059448201`，沿用自编译release签名。
- debug包名 `me.rerere.rikkahub.ops`，同版本，证书沿用原debug keystore。
- 两包DEX均存在paged/snapshot/source_truncated/workspace_result标记，均包含`ops-v2/query.sh`；源码HEAD a4341901，仅原有wrapper镜像改动未提交。
- 未自动安装，也未声称修复版Android实机复测通过。

以下保留构建启动记录：

- 启动PID：`89857`。
- 日志：`/mnt/coding/rikkahub-build/logs/ops-v2-paging-fix.log`。
- 命令：`./build.sh :app:assembleGithubDebug :app:assembleGithubRelease -x :web:buildWebUi`。
- 结束标记：`OPS_V2_PAGING_BUILD_EXIT=<rc>`。
- debug包名 `me.rerere.rikkahub.ops`，数据独立；release包名 `me.rerere.rikkahub`，用于原工作区（覆盖安装仍需既定签名放行条件）。两者均不自动安装。
- 下一次用户询问进度时只做一次状态查询，不sleep、不轮询。构建成功后再核验APK包名/签名/哈希与新符号并取包，不能拿旧产物当修复版。

尚未对修复版重新做Android装机测试，不把37项JVM回归称为实机验收。
