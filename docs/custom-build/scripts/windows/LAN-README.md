# RikkaHub SSH：同Wi-Fi / 手机热点测试包

源码96b94e15，3.1.2/code195，隔离包me.rerere.rikkahub.sshprobe。旧P0/P1包保留，可回退；不要卸载或覆盖正式RikkaHub。

## 验证状态

- 主APK+androidTest构建通过，签名沿用P0/P1原debug证书，版本/manifest/LAN类/ops资源已核验。
- JVM **654/654通过**（app598+workspace56）；包含新增网络策略与LAN状态机，核心53项另跑通过。
- pwsh脚本3项解析+14项主机地址策略检查通过，共17项。
- lint/格式检查仍有继承问题，见BUILD-STATUS.txt，不能称全检查通过。
- Android同Wi-Fi/手机热点地址枚举与直连、Windows OpenSSH实际连接仍待本轮用户验收；VS Code Server还没有安装/验证。

## 1. 先升级（USB仅用于安装，连接不再需要它）

安装PowerShell7，用pwsh；安装时adb在PATH内。PinHost/Connect/Sftp不需要adb，需Windows OpenSSH Client。

在解压目录运行：

```powershell
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode Install
```

这是-r升级，保留已有测试Rootfs/配置/host key。不必运行P0 Computer探针，也不要同时开它占2222。

## 2. 手机上开启局域网

设置→SSH开发连接→先停止已有实例→开启“局域网 / 手机热点直连”，确认风险。

- **同Wi-Fi**：手机电脑连同一受信任Wi-Fi，刷新并选择手机Wi-Fi地址。
- **手机热点**：先开启手机热点、电脑连它，再刷新并选择手机热点网关地址。不要选择电脑自己的DHCP地址。

手机界面显示接口名+具体IP；某些厂商热点接口可能无法识别，无候选时请反馈，不会退到0.0.0.0。选原本地READY工作区和原电脑公钥，保存并启动，等待RUNNING。通知应显示同一IP:port。

## 3. Windows直连

可拔掉USB。把下面两个值替换为手机当前UI显示内容：

```powershell
$Phone = '手机UI显示的IPv4'
$Fingerprint = 'SHA256:手机UI显示的主机指纹'
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode PinHost -HostName $Phone -ExpectedFingerprint $Fingerprint
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode Connect -HostName $Phone -IdentityFile .\p0-computer
pwsh -NoProfile -File .\windows\Test-RikkaSshLan.ps1 -Mode Sftp -HostName $Phone -IdentityFile .\p0-computer
```

私钥留在电脑。PinHost仅在扫描到的Ed25519 key指纹与手机UI完全一致后建立独立known_hosts；不关闭主机校验。同一工作区切换网络时host key应保持不变。

只绑定选定的具体私网IPv4，不监听所有网卡，不做公网穿透、UPnP或路由配置。访客网络/AP客户端隔离可能阻止互访，需要先确认网络策略，勿擅改防火墙。

## 4. 本轮请回报

1. Wi-Fi地址能否出现并直接SSH/SFTP；
2. 热点地址能否出现并直接SSH/SFTP；
3. 通知停止是否断开旧连接、移除通知、显示STOPPED；
4. 所选地址消失后是否停止（每2秒兜底检查）；
5. 切回仅本机+ADB是否仍正常。

换网络推荐先停再选地址启动，地址监测不等于SSID身份认证。完整清单见LAN-TESTING.md。不要上传私钥或整份未脱敏logcat。
