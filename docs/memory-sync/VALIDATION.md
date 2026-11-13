# 本轮验证结果

日期：2026-11-13。基线 `tsxc-github/rikkahub ops-v2@5ebbc986e68ad102d7ab6af4ca95fac10226779a`；独立工作树/分支为 `rikkahub-memory-sync` / `feat/memory-markdown-sync`。以下结果在源码提交前生成；后续发布不改变测试证据，未改原日用工作树。

## 已执行

完整入口：

```sh
pwsh -NoProfile -File tools/memory-sync/Run-MemorySyncTests.ps1 \
  -RepoRoot /mnt/coding/rikkahub-build/src/rikkahub-memory-sync \
  -BuildHome /mnt/coding/rikkahub-build \
  -LogRoot /mnt/coding/rikkahub-build/logs/memory-sync
```

最终报告：`/mnt/coding/rikkahub-build/logs/memory-sync/20261113-152834-5a75a220/result.json`，入口退出 **0**。JUnit 明细为同目录 `junit-counts.json`。

| 检查 | 实际结果 |
|---|---|
| Python helper/CLI | 21 项通过，0 失败 |
| App `*Memory*` JVM 回归 | 101 项通过，0 失败、0 跳过 |
| workspace JVM（含真实 SSH/RPC） | 58 项通过，0 失败、0 跳过 |
| `assembleGithubDebug` | 通过，包含 web-ui 构建任务，无跳过参数 |
| `assembleGithubDebugAndroidTest` | 通过，设备测试 APK 编译打包成功 |
| APK 包名/ABI/签名/目标包/SHA-256 | 通过，已进入 Host 模式自动报告 |
| Android 真机 UI、SAF 完整授权、后台/恢复 | NOT_RUN：没有连接的 ADB 设备 |
| 真机→ADB reverse→临时 SSH 端到端 | NOT_RUN：没有连接的 ADB 设备 |
| lint、全库格式、release 构建 | 未运行，不宣称通过 |

Host 合计 **180 项**，其中 Python 为真实独立进程的并发、重复请求、逐阶段崩溃/恢复、漂移保存与 CLI 草稿基线测试；JVM 同步引擎会调用真实 Python RPC 子进程，workspace 会建立真实嵌入式 SSH 连接。

最终实现另外完成过针对 `MemorySyncEngineTest` 的定向回归和两类 APK 构建，退出 0，随后上面的最终完整入口再次通过。覆盖手动单次同步不受“暂停自动同步”误拦截、旧冲突视图不能借后台更新 token 覆盖新内容。

Room 真正 Android SQLite 的 generation 并发、33 迁移、UI 与生命周期用例已编译进 instrumentation；未连接设备，不能把它们记为运行通过。JVM Fake DAO 用例不是 Android Room 事务运行证明。没有实际运行新界面，因此未取得真机视觉证据。

## APK 公开核验值

| 产物 | 包名 | SHA-256 |
|---|---|---|
| arm64 debug App | `me.rerere.rikkahub.memoryprobe` | `8b5788618b9c88a3e098f39e688cd2e5436aa7d1312de2f5d0901144fefacf23` |
| instrumentation | `me.rerere.rikkahub.memoryprobe.test` | `e07d834e35bb5f10d6fdc068fbbcfb042e52af0e84e50c1239b0ebcab35970cf` |

App 的 versionName 为 3.2.0、versionCode 为 196；两类 APK 使用相同 debug 证书：`7fb5c6c9015483f1c11518069f4817924d4c07ca5c937744a93d18831a2c9630`。instrumentation 的真实编译 manifest 目标为 `.memoryprobe`。签名私钥不在源码或交付包内。

## 验证中发现并修复

- PowerShell 不接受 Kotlin 式数字分隔符，修正临时设备 fixture 的超时字面量。
- Kotlin 2.4 的混合 SQL 参数数组类型推断报错，设备 fixture 明确使用 `arrayOf<Any>`。
- 新 build-tools 的 apksigner 输出为 `V2 Signer:`，自动核验兼容其格式，并仍要求单 signer、唯一 SHA-256 证书。
- `aapt2 dump badging` 不提供 instrumentation 目标字段；改读编译后 `AndroidManifest.xml` 的 instrumentation 节点，不放宽目标包检查。

所有失败尝试保留独立报告，没有将失败改成通过或跳过有关检查。最后报告才是本轮交付依据。

## 不产生的副作用与回退

- 没有自动安装/卸载 App、清数据、重装 Rootfs、导出真实用户记忆、部署 helper、开端口或调整 CT 资源。
- 当前测试 App 与日用 `.ops` / 官方包隔离；测试不需要触碰正式数据库。
- 真机测试需显式 `-Device -InstallTestApp -Serial ... -RequireDeviceSsh`，见 [TESTING.md](TESTING.md)。它仅清理自己创建的 scope/workspace/binding/临时 SSH fixture。
- 正式库若未来升级到 schema 33，不能直接用 schema 32 APK 降级覆盖；应在升级前保留可恢复备份。当前隔离包无需迁移日用库即可测试。
