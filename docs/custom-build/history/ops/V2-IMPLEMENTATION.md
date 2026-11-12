# ops-v2 实施交接

## 最新状态：2026-11-07 已合并上游3.1.2

HEAD `3adc2271`，上游基线 `fe5a28f9`。保留原ops-v2提交；兼容上游conversationId必填、后台shell任务start/output/kill的有界输出、running/killed状态及skippedBytes完整性说明。debug/release全量构建已成功（12m36s，含web），构建前及新编译模块类型的构建后回归均41项通过（后者1.002s），结束标记exit=0。arm64 release已取回 `artifacts/rikkahub-v2-3.1.2-3adc2271-arm64-release.apk`，versionName3.1.2/code195，原release签名、CRC/DEX/query资源和本地远端SHA256核验通过；SHA256 `716c6109be35f76c909a91e9507916cf5b5f19b431f6119e23343eed2635425d`。未自动安装，3.1.2实机验收未做。原构建PID4768已结束；日志 `/mnt/coding/rikkahub-build/logs/ops-v2-upstream-20261107.log`，结束标记 `OPS_V2_UPSTREAM_BUILD_EXIT`。不轮询、不自动安装；下次用户询问只查一次。详见 `UPSTREAM-MERGE-2026-11-07.md`。下面 a4341901 / 37项均为旧版历史。

## 装机反馈修复（历史）

当前 `ops-v2` HEAD **`a4341901`**。用户已对初版debug进行本地实机测试；上一轮日志也确认初版debug构建成功。二次截断缺陷已修复，新增页渲染→原生result工具→最终请求投影组合回归；**37项测试通过**。修复版debug与release已构建成功（4m32s，exit=0），签名/包名/DEX标记已核验，arm64 release已取回`artifacts/rikkahub-v2-a4341901-arm64-release.apk`，尚未安装/实机复测。构建日志 `/mnt/coding/rikkahub-build/logs/ops-v2-paging-fix.log`，结束标记 `OPS_V2_PAGING_BUILD_EXIT`；启动PID `89857`。不轮询、不自动安装。详见 **`V2-PAGING-FIX.md`**。

以下为初版实施记录，旧HEAD/旧构建日志不代表当前待出包版本。

## 初版状态（历史，2026-11-05）

- 用户批准按 PLAN-v2 重写，并补充 Markdown 应全量输出或给大预算。
- 已从 `base/v3.1.1` 新开 `ops-v2`；旧 `ops` 分支保持不变，旧 HEAD 另存 `archive/ops-641d6bf1`。
- coding 仓库：`/mnt/coding/rikkahub-build/src/rikkahub`。
- 当前 HEAD：`2e105a41`，3 个提交：
  - `4be9b847`：原生范围读取、分页/引用、请求投影、查询核心与回归测试。
  - `afbb7198`：工具和 ChatRequestFactory 接入、Markdown 端到端例外。
  - `2e105a41`：debug 包名后缀 `.ops`。
- 沙箱源码副本：`v2/`；补丁：`patches-v2/ops-v2.mbox`，可在上游基线用 `git am` 应用。
- 远端唯一保留的未提交环境改动为此前已有的 Gradle wrapper 镜像地址，不包含在补丁里。
- 未修改主机/CT 配置、未安装服务、未覆盖手机 APK。

## 已实现的策略

- 普通文本：6 KiB 原文页；按原行数和字节预算停止，cursor 可在超长行内部续接，不丢 UTF-8 字符。
- 本地：RandomAccessFile 范围读取，沿用 WorkspaceManager 的路径解析。
- SSH：SFTP 指定偏移的有界读取；没有经 shell 的 128K 输出上限，也没有整读 8 MiB 的限制。
- **Markdown 默认 512 KiB 原文预算**，未显式传 offset/limit 时不以 300 行限制；`.md/.markdown` 忽略大小写。普通历史正文的 32 KiB 折叠不适用于 Markdown，通用截断器也不再处理它。
- Markdown >512 KiB 时仍可用 cursor 精确续接，不承诺无限输出或绕过模型本身上下文窗口。
- 最终请求投影位于 limitContext 与 input transformers 之后；UI 原始历史不变。
- 普通正文按 16 KiB 阶梯分档折叠，目标合计 32 KiB；引用/状态/调用参数/schema 不在“正文预算”定义里。存储失败正确性优先，保留原文并报告 budget degraded。
- 去重仅在本次最终保留的正文中选锚点，且带有效备用 ref；不查数据库全历史来宣称可见。
- 引用保存原始采集文本、版本化 manifest、来源和完整性状态。作用域按会话隔离，最旧先淘汰，活动引用保护 5 分钟；容量不足明确失败，不返回死 ref。
- 旧 32 hex v1 ref 提供只读兼容。旧版已经丢掉的正文不能凭空修复。
- grep/glob 使用同一文件名 glob 选择，在限量之前过滤；NUL 帧避免冒号/换行文件名解析错误。rg 优先，literal 用 awk 兜底；无 rg 时 regex 明确不可用。
- grep 支持 filesOnly；无隐藏每文件8条上限，采用 N+1/预算检测；完成、limited、partial_error、timeout/error 分开。
- shell 维持上游采集限制，模型视图优先展示状态和 stderr；归档只包含确实采集到的输出。

## 已验证（真实执行）

`verify-v2-core.py` 在 coding 上调用现有 Kotlin 2.4.21 编译器，编译新核心源码、读取真实已编译 ai 模块（UIMessage、Tool、limitContext），执行 JUnit：

```text
JUnit version 4.13.2
............................
Time: 0.848
OK (28 tests)
```

覆盖 UTF-8 长行完整续接、CRLF/无末尾换行、空文件、来源变化、cursor 绑定、>普通预算的 Markdown 保留、Markdown >512 KiB 续读、原生 >8 MiB 文件尾范围、普通历史预算、可见集合去重、contextMessageLimit 和压缩检查点、原始历史不变、metadata、不足容量/引用淘汰/作用域、结果长行尾部、搜索 30 命中/5条限量、glob预过滤、literal反斜杠和空格、异形文件名、filesOnly、缺失路径/完成帧等。

另在沙箱实际执行相同查询脚本，rg 与 awk 均通过部分契约场景，结果 `v2-shell-results.json`。

**这不是完整 APK 测试通过。** 快速核心测试没有编译 ChatToolFactory/WorkspaceRepository/SSH 适配器，也没有验证 Android Java resources 的 APK 打包。曾尝试对所有接入点用缓存依赖独立 typecheck，前台超时，未得到通过结论；不计作验证。对应脚本 `verify-v2-integration.py` 仅留作排查材料，不推荐替代 Gradle。

## 后台构建已启动，不轮询

- PID（启动时）：`89017`。
- 日志：`/mnt/coding/rikkahub-build/logs/ops-v2-debug.log`。
- 命令：`./build.sh :app:assembleGithubDebug -x :web:buildWebUi`。
- 结尾会写 `OPS_V2_BUILD_EXIT=<rc>`。
- 构建只针对 debug；没有启动 release。
- 下一次用户催进度时，**只查一次日志状态/编译错误**；不要 sleep 或循环查。尚未声称构建成功。

单次查看（输出有界）：

```bash
ssh coding 'tail -n 70 /mnt/coding/rikkahub-build/logs/ops-v2-debug.log'
```

若编译失败，先修真实错误再重启后台任务；不要改 CT 内存、swap、CPU 等机器配置。

## 未完成的验证/后续

1. Gradle 全应用编译与 APK 构建结果；如有错误需修复。
2. 真实 SSH 适配器范围读取和 Android 本地实际 Rootfs/bind 路径行为。
3. Android APK 里 query.sh 资源加载、实际 Provider 消息编码/metadata、审批/取消/图片/压缩路径 smoke。
4. 同任务累计请求正文、schema、实际模型缓存/输入 token、往返次数基准；**尚无已证实的节省百分比**。
5. 长期状态/导出备份与会话作用域引用的用户体验；当前 ref 不是永久档案。
6. 如果要覆盖安装 release，先让用户确认；包名/versionName/更新提醒策略不变。

## 已知取舍

- Markdown 豁免历史折叠，文档很多时仍会明显增加模型输入，这是按用户本轮用途主动保留的例外。
- v2 新输出具有可信本地 metadata；已有无 metadata 的 v1 工具历史暂不批量重写/托管。不要把这一版说成“所有旧会话上下文都立即变小”。
- 查询会枚举/排序范围内文件，输出有界不等于文件树扫描成本为常数；应收窄 path。超时必须返回不完整状态。
- glob 明确只支持 basename 子集，不实现完整 rg glob/gitignore 语义。
- 文件 fingerprint 用于检测常见变化，不是对同长度同 mtime 恶意重写的强一致证明；ref 则保存本次实际采集的内容。
