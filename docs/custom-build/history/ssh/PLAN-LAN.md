# P1 扩展：同Wi-Fi与手机热点直连

用户明确要求两种都支持。基线ssh-p1 fe1a683c，隔离包.sshprobe；生产ops-v2/路由器/防火墙不动。

## 方案

- 默认保留127.0.0.1；用户显式切换“局域网 / 手机热点”并确认风险。
- 绑定用户选择的**具体RFC1918 IPv4地址**，不监听0.0.0.0或IPv6通配符。列出实际UP网卡的私有IPv4，Wi-Fi和热点都从接口枚举获取（热点不一定出现在Android默认网络列表）。
- 自动识别Wi-Fi/热点/桥/以太网常见接口，排除蜂窝rmnet/ccmni/pdp/wwan/clat及VPN/tun/wg/ppp等；不依赖隐藏热点API或权限。若厂商接口无法识别，明确无可用地址而不退化到全网卡。允许用户在候选地址中选，不声称某个接口名在所有厂商上必然是热点。
- 监听地址贯穿配置、runtime lease、readiness、port-release、状态、通知、UI与连接命令。LAN模式的loopback地址不再默认可用；ADB模式与LAN模式显式区分。
- 网络/IP消失时停止，不能自动迁移到另一个地址/接口或公网。使用Android网络回调+热点状态广播作事件触发，服务RUNNING期间另加轻量地址校验兜底（不在前台阻塞）。
- 老配置缺少模式/地址时默认loopback；持久host key、公钥认证、手动启停、通知停止不变。LAN配置显式保存，不在App冷启动重开。
- 仅选中地址接收连接；同网其他人可尝试认证，无法保证路由器不会转发该地址。提示只在受信任Wi-Fi/个人热点使用，不主动开路由映射/UPnP。

## 验证与交付

1. 新增纯Kotlin策略测试（合法私网、拒绝通配/公网/注入、网卡筛选、旧模式、网络消失）及原31项回归。
2. 新的测试入口/Windows验收全部用pwsh .ps1；Kotlin/JUnit本身保留。coding若无pwsh，使用工作目录下官方portable运行时，不改apt源、服务或CT资源配置。
3. 主APK/androidTest/相关JVM，lint/格式分别记录继承问题。后台监视构建，完成核验后另打LAN ZIP，不覆盖已可用P1/P0包。
4. 用户分开实测：同Wi-Fi直连、手机热点直连、热点关开或换Wi-Fi后停止、公钥/host pin、通知停止、回环模式回归。

## 状态

首版HEAD96b94e15已构建并静态核验，654项JVM通过（含核心53项），17项pwsh检查通过。lint/spotless继承问题未清，driver exit1。新LAN ZIP已放coding，详见LAN-IMPLEMENTATION.md；尚无Android同Wi-Fi/手机热点及Windows直连真机通过证据。
