# LAN / 手机热点实现状态

## 最新交付

- HEAD **96b94e159d5f1fcb379e598ce0d1049ecfb3b018**，patch `logs/lan/ssh-lan.mbox`；远端Spotless后提交源码已回取`source/`。
- assemble exit0（主APK/androidTest），app598+workspace56=**654项JVM测试全部通过**；格式化后的核心53项通过（0.179s）。
- lint还是67 errors / 348 warnings / 1 hint，无SshLan/SshDev/SettingSsh项；格式仅继承WorkspaceRepository.kt/WorkspaceReadChunk.kt两文件，driver exit1，不称全检查通过。
- 通过pwsh `Verify-LanPackage.ps1`核验两包签名/包名/版本、测试目标、service/specialUse/ACCESS_NETWORK_STATE、LAN DEX标记和ops query资源；报告`logs/lan/verification.json`。
- 主APK **8d057e8de78859fe96b6225e0a4f52364f48f0e8fca4e04a68f9d5ed48ffdb25**（63,551,970字节），沿用debug证书、.sshprobe、3.1.2/code195；androidTest仍52c65f99…dcee9。
- 通过pwsh `Publish-LanPackage.ps1`按白名单打包、比对复制前后hash、检查ZIP条目及解压读取；仅公开测试资产，无私钥/Rootfs/local.properties。新包 `/mnt/coding/rikkahub-build/downloads/ssh-lan-v3.1.2-windows.zip`，63,515,392字节；SHA256 **bbfe712ef3c0b8b70daebf67489fd6b1be1cd40dec3c7a7fc734afc6a07ee9b7**，同目录.sha256；元数据`logs/lan/package.json`。
- 旧P1/P0 ZIP保留。新包Windows脚本无需ADB即可PinHost/Connect/Sftp，只有安装或loopback forward需要ADB。脚本17项pwsh检查不等于Windows真实连接通过。
- 手机同Wi-Fi/热点枚举、网络变化、通知停止和Windows直连均待用户测试，不把已通过的P1回环误算为LAN通过。

以下为实施与构建启动记录，后台构建已结束。

## 目标

用户要求同Wi-Fi和手机热点都支持。沿用ssh-p1隔离分支、.sshprobe、3.1.2/code195；基线fe1a683c已备份archive/ssh-p1-before-lan-fe1a683c。不动正式ops-v2、CT资源、路由、防火墙、UPnP。

## 代码

- SshDevConfig新增LOOPBACK/LAN、bindAddress、bindInterface。旧JSON缺字段默认loopback。LAN仅严格数字RFC1918 IPv4，禁止通配/公网/主机名/IPv6与注入。
- SshLanNetworks：枚举UP且非loopback/point-to-point接口的IPv4；识别wlan/swlan/wifi/ap/softap/br/bridge/eth/enp/enx/eno常见接口，拒绝rmnet/ccmni/pdp/wwan/clat及tun/wg/ppp等。热点通过实际网卡枚举，不依赖Android默认网络必须是热点或隐藏热点控制API。OEM未知接口明确无候选，不fallback到全网卡。
- ACCESS_NETWORK_STATE仅加在GitHub overlay；Android Wi-Fi/以太网事件与热点广播触发刷新，LAN STARTING/RUNNING期间每2秒额外核对具体接口+IP。消失时调用原controller.stop，不自动迁移。此策略不认证SSID；换网建议手动停再开。
- LAN地址贯穿JSON、sshd ListenAddress、lease、readiness/banner/socket probe、release验证、状态/通知和UI命令。仅绑定所选IP，LAN不同时监听loopback。旧租约地址消失时不尝试绑定不存在IP，也不转绑别的网卡；仍先核对原进程。
- UI显式风险确认、LAN开关、候选地址/刷新/选择、禁用原因提示；默认仅本机。host key与用户公钥不变。
- Windows脚本Test-RikkaSshLan.ps1：Install/Forward才需要adb；LAN PinHost/Connect/Sftp直接连接私有IP，按手机指纹精确pin，独立known_hosts。没有启动公网服务、上传私钥或关闭主机校验。

## 已执行测试

- coding工作目录安装官方portable pwsh7.6.9（无apt源/系统服务改动）。官方发布页：https://github.com/PowerShell/PowerShell/releases/tag/v7.6.9 。下载经国内GitHub代理，SHA256核验1da6d6d6035d651f20e70ddee7c4c15ac6ab8b9fb40ee92240d91263f28a884b，实际运行7.6.9。
- **53项真实Kotlin/JVM通过（0.198s）**：原31+新增网络策略20与controller LAN切换2。入口全部pwsh `Build-Lan.ps1 -Stage Core`，编译真实源码，不是Python镜像。
- **17项真实pwsh脚本检查通过**：Build-Lan/Watch/Windowshelper三个AST解析+14个地址输入用例。入口Test-LanPwsh.ps1；不把Linux pwsh验证当作Windows OpenSSH真机通过。

## 后台构建

- PID17234，`/mnt/coding/rikkahub-build/tools/pwsh-7.6.9/pwsh -NoProfile -File Build-Lan.ps1 -Stage Full`。
- 日志 `/mnt/coding/rikkahub-build/logs/ssh-lan/driver.log`，stage-exit-code和最终exit-code/SSH_LAN_BUILD_EXIT。
- 包含feature格式化（还原无关继承格式变化）、提交/mbox、核心53项重跑、主APK/androidTest、相关JVM、lint/spotless独立记录。
- 后台监视任务**85f3bee5**（SSH执行Watch-LanBuild.ps1），每30秒，最终退出码出现即结束、最长约1小时。用户已授权后台轮询。
- 尚未查最终构建状态/核验新APK；当前coding P1 ZIP还无LAN功能。待完成后另发LAN ZIP，保留老P1/P0可回退。

## 待验收

Android真实Wi-Fi/热点地址枚举、监听/权限/断网状态、Windows直连SSH/SFTP/转发、通知停止、仅绑定所选地址、回环回归。详见LAN-TESTING.md。P2 VS Code Server仍未开始。
