# P1：内置按需SSH管理（P0通过后的实施规划）

## 门槛与依据

P0在Android16/API36/arm64、Alpine musl环境上通过：已归档自动报告+用户确认instrumentation OK；Windows电脑SSH/交互PTY/Ctrl-C/SFTP/本地端口转发、STOP及结束检查均由用户确认。最终computer原始报告未上传，不能声称所有原始日志齐备。P0通过不等于FGS/休眠可靠或VS Code Server兼容。

## 目标与分支

基于3.1.2+ops-v2的隔离功能分支/worktree继续，不直接修改当前生产ops-v2。保留`.sshprobe`隔离包，完成编译与真机验收后再讨论合并/发布。按 `IMPLEMENTATION-SPEC-v1.0.md`，不引入聊天会话owner、不做同步、不安装VS Code Server。

## 实施顺序

1. 重读新基线AGENTS与相关后台进程API，核对Android当前target SDK及官方FGS要求后，单独论证服务类型，不照搬dataSync/伪造connectedDevice或specialUse。
2. 独立SshDevManager：单实例状态机，LOCAL/READY/依赖检查、公钥合法性、持久host key与配置、sshd-t、实际监听握手、异步退出和完整句柄清理。配置/密钥在正式私有路径原子写入；不碰既有用户authorized_keys。
3. 按需Foreground Service与通知停止按钮：用户明确启动，默认关闭，不自动恢复、不随开机启动。工作区删除/端口占用/进程退出/系统超时/清理失败有可观测错误。
4. 设置页：选择本地工作区、添加公钥、端口、显示指纹与状态、启动/停止。默认仅127.0.0.1；LAN监听及网络暴露必须按用户意图单独确认，不自行开启或修改防火墙。
5. 单测状态机与安全输入；APK构建+相关JVM+lint+spotless分别记录。继承检查问题与新增问题分开，不设baseline掩盖、不批量调不相关源码。
6. Android/Windows验收：原SSH/SFTP/PTY/转发/停止流程、前台通知/退后台/锁屏/重复启动/异常退出/重新进入状态；不保证24x7保活。通过后再进入P2官方VS Code Remote-SSH（当前musl/PRoot不能默认兼容）。

## 当前状态

已创建隔离分支ssh-p1并实现首版，HEAD fe1a683c；主APK/androidTest构建与签名/manifest/DEX核验通过，JVM632项通过（含新增31项核心）；lint与spotless继承问题未清，总driver exit1。新版pwsh测试ZIP已上传coding下载目录，等待P1真机验收，不能称全部P1已通过。详情见P1-IMPLEMENTATION.md。不更改CT配置、生产App、网络或用户数据。
