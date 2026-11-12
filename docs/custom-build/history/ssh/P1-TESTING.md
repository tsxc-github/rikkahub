# P1 真机验收计划（待构建核验后执行）

P1 APK已完成构建、JVM和静态打包核验，尚未做P1真机验收。coding的`ssh-p1-v3.1.2-windows.zip`包含本页功能；旧P0 ZIP不包含。只升级`.sshprobe`隔离App，不卸载，以保留P0测试Rootfs；不要覆盖正式App或导入正式数据。

## 必测路径

1. 设置→SSH开发连接：确认默认STOPPED、仅回环；选择已有LOCAL/READY测试工作区，粘贴电脑Ed25519公钥，不上传私钥；端口2222。
2. 允许通知，保存并启动。检查RUNNING之前执行依赖/config/监听握手，显示host fingerprint；通知含工作区/端口/停止动作。
3. `adb forward --no-rebind tcp:2222 tcp:2222`。P1使用新的**持久**host key，不等于P0临时key；使用单独known_hosts文件，人工比较UI指纹，不关闭主机检查。
4. Windows普通SSH/交互PTY/Ctrl-C/SFTP同文件读写/实际-L转发，与P0相同。正常服务不需要启动instrumentation；不要同时运行P0探针抢2222。
5. App退后台、锁屏后分别尝试连接，记录系统行为，不保证永久保活。不要清除最近任务来代替普通退后台；当前设计清除任务会主动停SSH。
6. 设置页停止、通知停止各测一次。保留一个活动SSH观察断开，检查新连接被拒绝、通知消失、状态STOPPED；手机普通终端/Agent继续可用。
7. 再次开启同一工作区，host fingerprint应保持一致；切换工作区需先停止，不能误复用旧实例。重复启动不产生多份进程。
8. 强制停止隔离App后重新打开：不自动开放SSH。若发现旧记录/疑似遗留进程应明确ERROR且fail-closed，停止/重试清理；不能显示绿色RUNNING或盲杀其他PID。
9. 删除工作区或Rootfs变BROKEN/非READY，运行服务应停。停止失败要保留owner/ERROR与重试入口，不宣称已停。

## 失败分支

- 未选工作区、远程SSH工作区、Rootfs未READY、sshd缺失、端口占用。
- 错误/重复/多行注入/私钥/带authorized_keys选项的公钥：拒绝，不写现有用户authorized_keys。
- 通知权限拒绝、通知渠道关闭、FGS被系统拒绝：无监听/无假RUNNING，并有可读错误。
- 启动中取消、启动失败后再次启动、服务异常销毁：不得留下没有通知但仍监听的实例。
- 点“刷新本次运行日志尾部”，读取有界尾部且不输出私钥/环境/工作区内容。

## 留档

记录APK SHA256/构建SHA、Android/API/ABI、Rootfs/OpenSSH版本、每项结果、系统事件顺序。不要直接公开完整logcat，先检查敏感信息。P1通过不等于官方VS Code Server/Remote-SSH通过；P2另做musl/PRoot兼容检查。
