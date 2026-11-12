# 同Wi-Fi / 手机热点直连验收（新APK核验后执行）

LAN版HEAD 96b94e15的APK已构建、签名/manifest/DEX静态核验，654项JVM测试通过，等待本轮手机/Windows真机测试。请使用`ssh-lan-v3.1.2-windows.zip`，旧P1下载包不包含LAN开关。

## 共同准备

- 仅升级`.sshprobe`隔离包，不卸载，保留测试Rootfs/公钥/持久host key。
- 手机设置→SSH开发连接→选原本地READY工作区。保持原电脑Ed25519公钥。
- 打开“局域网 / 手机热点直连”，确认风险；点“刷新Wi-Fi / 热点地址”，选择具体接口+IPv4。
- 不绑定0.0.0.0/IPv6通配，不改路由/防火墙/UPnP；这不保证路由器没有他人配置的映射，仅建议受信任网络。网络需允许设备互访。

## A. 同一Wi-Fi

1. 手机与电脑连接同一受信任Wi-Fi。手机列表通常为wlan0的192.168/10/172.16-31地址，以实际UI为准。
2. 选中该地址、保存并启动，看到RUNNING、通知显示同一IP:port。
3. USB可拔掉；Windows连接手机IPv4，不能使用127.0.0.1或adb forward冒充LAN测试。
4. 若有访客网络/AP客户端隔离，可能无法直连；先确认网络策略，不擅自改路由器。

## B. 手机个人热点

1. 先停止现有SSH。手机开启个人热点，让电脑连此热点。
2. 刷新地址，选择手机热点接口的IPv4（可能ap0/ap_br_wlan*/wlan1/swlan*/br*，不假定固定192.168.43.1）。
3. 要选**手机热点网关地址**，不是电脑从DHCP拿到的客户端IP。
4. 保存并启动后，用电脑直连UI显示的IP；不需要ADB或热点自动控制权限。
5. 某些OEM接口不在安全识别范围，可能暂时没有候选地址；请反馈UI/接口信息，不会退化到监听所有网卡。

## Windows / pwsh

LAN包脚本`windows/Test-RikkaSshLan.ps1`仅Install/Forward需要adb。PinHost/Connect/Sftp不依赖adb。

```powershell
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode Install
# 手机按上面的Wi-Fi或热点步骤启动后，替换这两个值：
$Phone = '192.168.43.1'
$Fingerprint = 'SHA256:从手机UI复制'
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode PinHost -HostName $Phone -ExpectedFingerprint $Fingerprint
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode Connect -HostName $Phone -IdentityFile .\p0-computer
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode Sftp -HostName $Phone -IdentityFile .\p0-computer
```

私钥始终留在电脑，不上传。PinHost必须与手机UI指纹比较；每IP/port用独立known_hosts，不关闭校验。同一工作区从loopback切到Wi-Fi/热点，host key应保持一致。

## 停止与网络变化

- 保持一个活动SSH，分别用页面停止、通知停止检查：连接断开、通知消失、UI STOPPED、重新连接不能成功。
- 关闭热点/关闭Wi-Fi使所选地址消失：活动LAN服务应在地址检查后停止，不自动换到另一地址；当前除事件刷新外每2秒核对一次。
- 切换网络推荐先手动停止，再刷新并选地址启动。监测目标是具体接口/IP是否仍存在，不把它当作SSID身份认证或路由防火墙。
- 同时有Wi-Fi和热点时，只允许连接所选地址，另一个地址不应出现SSH监听。
- 停止后切回仅本机，应回归127.0.0.1+ADB；LAN IP不应继续监听。

## 交付判据

同Wi-Fi与手机热点分别留结果；测试SSH/PTY/SFTP/转发/通知停止/地址消失/回环回归。Windows脚本已在coding pwsh下通过语法解析与14项主机地址策略测试（共17项），不等于Windows OpenSSH真实直连已通过。手机LAN与热点功能仍待用户设备实测。
