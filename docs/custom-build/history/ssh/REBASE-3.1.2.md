# SSH P0 迁移到3.1.2 + ops-v2（2026-11-07）

用户选择：先rebase到新版本，再由Windows电脑测试。

## 计划

1. 保存ssh-p0旧HEAD与wrapper本机镜像补丁。
2. 仅在独立worktree `/mnt/coding/rikkahub-build/src/rikkahub-ssh-p0` 执行 `git rebase --onto ops-v2 068dea4917e534cb3ed186ae963df98e66c084a4 ssh-p0`，迁移唯一探针提交。目标ops-v2=`3adc2271`（上游3.1.2）。不迁移整个feat分支，避免重复上游已合入的功能。
3. debug applicationIdSuffix冲突保留`.sshprobe`；原ops-v2分支及其工作树不变。必要适配只改探针，不更改生产功能。
4. 后台构建主debug和androidTest，运行app/workspace JVM测试；lint与spotless独立记录退出码，不因lint失败遗漏格式检查。存在上游/继承ops-v2格式问题时报告实际范围，不批量改不相关源码、不创建baseline掩盖问题。
5. 核验两APK版本/包名/目标包/签名/哈希，取回并提供Windows PowerShell安装/自动验收脚本及电脑模式说明。
6. 用户执行真机与电脑测试之前，不宣称P0通过、不进入P1 UI、不安装VS Code Server。

## 迁移前状态

- ssh-p0唯一提交295bfede，基线068dea49；原仅2文件235增1删。
- 旧主APK和instrumentation已构建，app/workspace JVM任务成功，但检查阶段lint失败；错误位于旧基线UI文件，原脚本因此未运行spotlessCheck。不能称旧构建全检查通过。
- 仅wrapper环境改动未提交，无活动构建。
- 本沙箱无可用ADB服务/设备连接；Windows端执行由用户完成。

## 已执行

- `git rebase --onto 3adc2271 068dea49 ssh-p0`成功，仅重放探针提交。唯一冲突在debug包名，保留`.sshprobe`；新HEAD `7f784084`，差异仍限2文件。
- 旧HEAD备份`archive/ssh-p0-before-v312-20261107`；wrapper环境改动已恢复；node_modules既有构建symlink加入本地exclude，不进补丁。
- 新构建脚本`build-p0-v312.sh`，远端同名；PID6270，日志`/mnt/coding/rikkahub-build/logs/ssh-p0-v312/driver.log`，各阶段独立`*-exit-code`，最终`exit-code`及`SSH_P0_V312_EXIT`。
- 新构建包含web，不跳过；assemble成功后独立运行unit/lint/format，lint失败也会执行spotlessCheck。未运行spotlessApply批量改继承ops源码。
- Windows脚本与说明已准备（PowerShell5.1/7语法目标）；本环境没有PowerShell/ADB设备，不冒称Windows脚本或真机测试已通过。脚本只装/停止隔离包，不删除数据；检查instrumentation OK与完整报告，而非只看adb退出码；SFTP测试文件用UTF-8无BOM及LF。
- 新APK、退出码、签名、SHA256尚待下一轮单次查询后核验取回。不得使用旧P0产物。

## 本轮查进度与coding下载包

- 主debug和androidTest：assemble exit0，2m45s；app545+workspace56=601项JVM测试，全部无失败/错误/跳过，unit exit0。
- lint exit1，67 errors / 348 warnings / 1 hint，位于探针未修改的既有UI代码；spotless exit1，报告继承的WorkspaceRepository.kt与WorkspaceReadChunk.kt格式问题。driver最终exit1，不声称全部检查通过，不擅自批量修UI/格式。
- 两包签名验证通过，debug证书沿用7fb5c6c9015483f1c11518069f4817924d4c07ca5c937744a93d18831a2c9630；主包.sshprobe，version3.1.2/code195/arm64；测试包.sshprobe.test且instrumentation精确指向.sshprobe。
- 主APK SHA256`c448cdd4dc686e349e3873127684b2c1f16c47a4c9628b347c9781b2cd67233a`；测试APK`52c65f99b1403f09e87a4e2d2222f78e22ba77c50c9ffe19314bb111906dcee9`。两包ZIP CRC通过，测试DEX含探针类及成功/电脑模式标记；这不是设备运行验收。
- 应用户要求，脚本和说明已上传coding，连同两包/BUILD-STATUS/README/SHA256SUMS放`/mnt/coding/rikkahub-build/downloads/ssh-p0-v3.1.2/`；整包`/mnt/coding/rikkahub-build/downloads/ssh-p0-v3.1.2-windows.zip`，59,784,273字节，SHA256`baa64be9e2e6d246c3d6e7461348504c276eb24cc4707a6ace9088496687f377`，ZIP完整性检查通过。
- 未新增HTTP下载服务或端口；使用现有SSH/SFTP下载。包内无私钥、local.properties、用户Rootfs/文件；Windows脚本和实机结果仍未验证。

## 边界

不改CT配置，不开LAN/公网，不动正式数据，不导出local.properties、签名私钥或Rootfs，不自动安装手机APK。
