# P1 首版实施记录

## 最新：构建/静态核验完成，交付P1真机测试

- 当前HEAD **fe1a683c**；P1三提交d01ce0e3/f9d80f34/fe1a683c，完整补丁已取回`logs/p1/ssh-p1.mbox`，格式化后源码快照已同步`source/`。
- 主APK+androidTest assemble exit0；app576+workspace56=**632项JVM全部通过**，新增真实核心31项也通过（0.174s）。
- lint exit1仍为**67 errors / 348 warnings / 1 hint**，无SshDev*/SettingSsh报告项；spotless只报继承的WorkspaceRepository.kt/WorkspaceReadChunk.kt，与P0一致。不做无关修复/抑制，总driver exit1，不称全部检查通过。
- 主包.sshprobe，3.1.2/code195/arm64；测试包.sshprobe.test；两APK签名验证通过，沿用原debug证书7fb5c6c9…9630。manifest核验SshDevService不导出、specialUse及真实用途property；DEX含controller/manager/service/settings route，保留ops-v2/query.sh；ZIP CRC通过。
- 主APK SHA256 **96d0373aa49c8cf4be1e16924c4ae82fa56b2afdc321faa2e23c4e1f1a3df92a**；androidTest未变，52c65f99…dcee9。
- coding交付目录 `/mnt/coding/rikkahub-build/downloads/ssh-p1-v3.1.2/`；整包同级 `ssh-p1-v3.1.2-windows.zip`。仅白名单打包两APK、pwsh脚本、README/测试清单/BUILD-STATUS/SHA256SUMS；无密钥/Rootfs/local.properties。ZIP最终SHA见同级`.zip.sha256`。
- Windows入口 `pwsh -NoProfile -File .\\windows\\Test-RikkaSshP1.ps1 -Mode Install`；主APK原包名/签名可-r升级保留测试Rootfs，androidTest使用-r -t。新增PinHost步骤对照手机UI指纹后才写专用known_hosts。
- **P1运行时、FGS/通知、Windows新脚本尚未真机验证**，不把P0通过或静态DEX检查当作这部分通过。不安装VS Code Server、不覆盖正式ops-v2。

以下保留实现与构建迭代记录，其中“后台构建中/下载还是P0”属于历史。

## 基线与隔离

- P0已通过自动真机与Windows手动验收（见P0-RESULTS.md）。
- 在原隔离worktree创建`ssh-p1`，基线P0 `7f784084` / ops-v2 `3adc2271` / 上游3.1.2。
- 生产ops-v2分支/工作树不改；包名沿用`.sshprobe`，不自动安装，不改CT或防火墙。

## FGS用途核对

已核对Android官方Foreground service types页面（页面标记更新2026-10-28）：
https://developer.android.com/develop/background-work/services/fgs/service-types

本功能为用户主动开启、带持续通知和停止入口的交互式本地开发SSH服务，不是dataSync，也不伪造USB权限/Bluetooth或CHANGE_NETWORK_STATE来满足connectedDevice检查。首版采用specialUse并写明真实用途，仅`app/src/github/AndroidManifest.xml`声明；Play flavor无新增服务/类型权限，UI/manager也拒绝Play构建启动。本项目没有申请或获得Play用途审核；不得承诺商店可发布或永久保活。

## 已写实现（待编译/验收）

- `ssh/SshDevConfig.kt`：单行Ed25519公钥、Base64与SSH wire格式验证、去注释按key去重、UUID/端口/数量检查，拒绝私钥/authorized_keys选项/换行/控制字符。
- `ssh/SshDevController.kt`：无Android依赖的可测状态机，单实例/幂等/先停后换、真正readiness后RUNNING、异步退出不重开、取消启动后收回句柄、停止失败保留owner供重试。
- `ssh/SshDevManager.kt`：独立owner、私有原子配置、复用WorkspaceManager后台句柄。仅LOCAL/READY；host key在Rootfs `/root/.rikkahub-ssh`持久保存，自己的authorized_keys不覆盖用户原文件，严格权限与路径检查；sshd-t、FileObserver监听+有界banner确认，前台-D-e。
- 同文件的旧租约恢复：核对pid/任务标识，发现旧进程或不能确认时fail-closed，要求Android强制停止隔离包后重试；**不冒险向复用PID发信号，不承诺自动杀掉所有异常遗留进程**。新建Rootfs/旧进程记录损坏等需单独故障注入验收。
- `service/SshDevService.kt`：用户动作启动、START_NOT_STICKY、通知停止按钮、无boot自动启动、退出/超时清理、系统拒绝可见错误。
- `SettingSshPage.kt`及settings/navigation/DI钩子：MD3 CardGroup，工作区、公钥/端口、状态/指纹、停止/重试、ADB/SSH示例、通知权限、安全提示。**不提供LAN开关**，等待单独授权后再做该扩展。
- 新增纯Kotlin测试覆盖公钥安全输入、状态/幂等/切换/停止失败重试/异常退出/取消/服务拒绝与停止竞态。运行脚本`verify-p1-core.py`编译真实源码，并非Python镜像实现。

## 当前验证状态

源码已同步coding。第一次真实核心回归30项有1项失败：异常退出后清理成功却保留ownsProcess=true；修正状态机，并新增恢复失败检查后，**31项全部通过（0.157s）**；Spotless格式化后再测31项也通过（0.162s）。这不是Android适配/FGS/设备测试。

第一次Android构建对应提交`d01ce0e3`（10文件975增行），实际编译只报Android适配stopAndWait的Unit?返回类型错误；已修正为显式Unit。另审阅修正service重入/旧ERROR初值导致提前stopSelf的竞态：按requestEpoch隔离回调，忽略旧terminal直到本次active/完成，onDestroy仍收回未完成启动所有权。该修改已同步并纳入重编。

**当前后台构建PID9589**，脚本`/mnt/coding/rikkahub-build/build-p1.sh`，日志`/mnt/coding/rikkahub-build/logs/ssh-p1/driver.log`，结束标记`SSH_P1_BUILD_EXIT`及`exit-code`。脚本会先只保留feature格式化（还原2个继承ops格式改动），提交并导出`ssh-p1.mbox`，核心31项→主debug/androidTest→相关JVM→lint/spotless独立记录。首轮日志归档`ssh-p1-initial-review/`，中间服务复查轮归档`ssh-p1-service-review/`；中间轮只对本任务setsid进程组发SIGTERM，未动其他服务/daemon或CT配置。用户最新允许后台轮询构建进度，并要求以后测试脚本使用pwsh。已单次确认assemble exit0、unit运行中；后台监视任务`1221bebb`每30秒查询一次日志状态，最终exit-code出现即结束，最长约1小时；脚本`watch-build-progress.py`仅负责监视，不是测试实现。后续交付测试/验收入口用PowerShell `.ps1`和`pwsh`命令；Kotlin/JUnit源码测试仍保留原生框架。

完整APK、JVM整套、lint/格式和设备验收尚未给通过结论；**coding现有Windows下载ZIP仍为P0**，没有新设置页，不会拿它冒充P1。沙箱`source/`保留手写源码，coding另经Spotless格式化，最终交接应以coding已提交版本与mbox为准，勿把本地未格式化全树再覆盖远端。

## 后续

1. 先解决核心测试真实失败，再完整Gradle编译主debug与androidTest。
2. 只对新feature改动做格式化；继承UI lint/ops格式问题独立记录，不创建baseline隐藏。
3. 成功后核验包名/签名/新类与manifest，保留P0可回退包，上传新测试包到coding。
4. 用户真机验证设置启动/通知停止/重复启动/后台锁屏/强杀恢复；普通SSH/SFTP/转发回归。
5. P1验收后，才做VS Code Server musl/PRoot兼容性P2。
