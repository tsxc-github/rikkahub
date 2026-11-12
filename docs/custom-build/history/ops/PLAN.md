# RikkaHub 改造方案：有界工具面（ops 思路落地）

2026-11-05 写于 coding CT 115，针对 **master 3.1.1**（versionCode 194）的真实代码核对过；早先写的 2.5.6 来自沙箱旧副本，已作废。
目标不是把 dsh-ops 搬进来（那是 Node/DSH 插件，进不了 Kotlin 应用），而是抄它四条可移植的机制：

1. 读文件**分页**（offset/limit + 行号 + 续页提示）
2. 超预算输出**进引用、可用工具翻页**（而不是只丢一句"自己去 shell cat"）
3. 有界搜索原语 `grep` / `glob`（替掉一次 grep = 一次完整 LLM 往返）
4. 预算门槛与降级文案统一（可调常量）

明确不做（要动宿主语义，收益不抵风险）：PTC 批量执行、并行工具调用、动态工具披露。

~~读取去重~~：原先判为"不可移植"，2026-11-05 重新评估后**改为可做**，判据换成「同一段字节是否仍在这条会话当前的消息列表里」，见 §9；ops 原版判据（内容确实进了最近一次成功请求）仍然不移植。

## 0. 现状锚点（2026-11-05 核对）

| 事实 | 位置 |
| --- | --- |
| 工具仍是**串行**执行（每多一轮 = 一次完整上下文重发） | `ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt:162` `for (tool in tools)` |
| 截断装饰器**已从 GenerationLoop 搬进 ChatToolFactory** | `app/.../data/ai/tools/ChatToolFactory.kt:95-131`（`withOutputTruncation` / `truncateOutput`） |
| 门槛常量 | 同文件 `MAX_TOOL_OUTPUT_CHARS = 32*1024`、`TOOL_OUTPUT_PREVIEW_CHARS = 4*1024`（L25-26） |
| 截断只在**本地 rootfs** 生效 | 同文件 L95-99：`if (canReadToolOutputs) tools.map(::withOutputTruncation) else tools`，门槛是 `capabilities()?.rootfs == true`；`Workspace.kt:18` 里 SSH = `rootfs=false` |
| workspace 工具只有 4 个 | `WorkspaceTools.kt:76/115/153/221`（read_file / write_file / edit_file / shell） |
| read 无 offset/limit，整文件回灌；图片分支在同一个工具里 | `WorkspaceTools.kt:62-99` |
| 文件原语层**已有 glob/grep**（Kotlin，走 java.nio） | `workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt:105` `glob()` / `:121` `grep()`；`WorkspaceManager.kt:192/202` 包了一层 |
| 工作区已抽象成 backend（local proot / SSH 远程） | `workspace/.../WorkspaceBackend.kt`、`LocalWorkspaceBackend.kt`、`ssh/SshWorkspaceBackend.kt` |
| 仓库入口：`readText(id,path)`、`executeCommand(id,cmd)`、`writeText`… | `app/.../data/repository/WorkspaceRepository.kt:226/335` |
| `/tool_outputs` 是本机目录挂进 rootfs | `di/RepositoryModule.kt:77-78`（source=filesDir/tool_outputs，target=/tool_outputs） |
| 工具对象契约：`Tool(name, description, parameters=InputSchema, needsApproval, execute suspending)` | `ai/.../core/Tool.kt` |

> ⚠️ `/workspace/projects/rikkahub-src-工具调用-分析.md`（10-09 写的 GenerationLoop L221 那套）**已经过期**：生成循环迁到 `ai/` 模块的 AgentLoop，截断迁进 ChatToolFactory，workspace 重写成 backend 架构。以本文为准。

## 1. 三条共存纪律（比代码本身重要）

1. **新逻辑进新文件**；对上游文件的改动只允许"钩子"，每处 ≤10 行，且用 `// region ops-*` / `// endregion` 包住。
2. **锚点用字符串不用行号**：上游一改，行号全漂。PLAN/脚本里一律用 `grep -n` 的锚点（例如 `fun createWorkspaceTools(`、`appendLine("Full output saved to`）。
3. 改动全部无设置项、无数据库/DataStore 迁移 —— 少一个 schema 少一类冲突。

## 2. Commit 1：read 分页

**改一个文件**：`app/.../data/ai/tools/WorkspaceTools.kt`（+约 45 行）

```kotlin
// parameters 增加两个可选字段
offset: integer  "1-based start line (default 1)"
limit:  integer  "max lines to return (default 300, max 2000)"
```

行为：
- 不带 offset/limit → 保持今天的整文件返回（零行为变化，先别激进）
- 带任一参数 → 按行切，`limit` 默认 300，字符预算 16000（先到先停）
- 返回格式（首行元信息 + 行号 + 续页提示）：

```
file /workspace/docs/foo.md | lines 531-568 of 1204 | 24594 B
   531|     private fun maybeTruncateToolOutput(
   ...
   568|     }
[more: 636 lines → offset 569]
```

- 单行超过 400 字符截断并标 `…(+N chars)`
- 图片分支、8MB 上限、`needsApproval` 均不动

**为什么先做它**：现在一个 >32K 的文件被截断后，模型只能 `cat`/`grep`（串行往返），这一步能直接消掉"读大文件"最常见的失败路径。

## 3. Commit 2：结果引用 + `workspace_result`（核心）

**新文件** `app/.../data/ai/tools/OpsResultStore.kt`（约 90 行）

```kotlin
class OpsResultStore(private val dir: File) {
    fun put(text: String, tool: String): String        // 写 <uuid>.txt，返回 ref id，顺带 prune()
    fun page(ref: String, offset: Int, limit: Int): PageResult  // 行切片 + totalLines/totalBytes
    fun prune()                                        // 保留最近 50 个 / 200MB
}
```

- 存储位置复用现有 `filesDir/tool_outputs/`，文件名 `<uuid>.txt`，元数据从文件本身推（行数、字节）
- ref 校验：只接受 `[0-9a-f-]{36}`，拒绝路径穿越
- 不做内存缓存，不做数据库

**新文件** `app/.../data/ai/tools/OpsResultTool.kt`（约 60 行）

```
workspace_result { ref: string, offset?: int, limit?: int }
→ file tool_outputs/<ref> | lines 1-64 of 5000 | 81234 B
      1| ...
[more: 4936 lines → offset 65]
```

**改上游文件（2 处钩子）**：`ChatToolFactory.kt`
- `tools` 里追加 `createOpsResultTool(resultStore)`（1 行）
- `truncateOutput()` 改写：门槛 32K → 16K（常量改名 `OPS_INLINE_LIMIT`），降级文案从"用 shell cat/grep"改成"`workspace_result { ref }` 翻页"（约 10 行，`// region ops-budget`）
- 顺手删掉 `canReadToolOutputs` 的 `rootfs == true` 门槛 —— ref 走 app 内文件，**SSH 远程工作区也能翻页**（修掉现有 bug）

**收益**：一次大输出不再等于 32K 换 4K 预览；模型可以按 64 行/页精确推进，成本可控。

## 4. Commit 3：`workspace_grep` / `workspace_glob`

**新文件** `app/.../data/ai/tools/OpsSearchTools.kt`（约 120 行）

```
workspace_grep { pattern, path?="/workspace", glob?, regex?=false, ignoreCase?=true, max?=80 }
workspace_glob { pattern, path?="/workspace", max?=200 }
```

实现取向：**优先走 `repository.executeCommand`**（local proot 和 SSH 后端都吃），Kotlin 侧解析 `path:line:text` 并强制预算：

- grep：`rg -n --max-columns 300 --max-columns-preview --max-count 8 <pat> <path>`，没有 rg 时 fallback `grep -rn --include=<glob>`
- glob：`find <path> -name <pat>` / `ls -d`，按路径排序
- 输出：

```
src/foo.kt:55: private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
[hits: 80 shown / 156 matched; omitted 76 → narrow pattern/glob]
```

不落地走 `WorkspaceFileSystem.grep()/glob()`：它只对本地 backend 有效（要传 `root: File`），SSH 工作区会落空；而且它没有行号预算。保留它给 UI 用。

**改上游文件（1 处钩子）**：`ChatToolFactory.kt` 追加 `addAll(createOpsSearchTools(...))`（1 行，`// region ops-tools`）

## 5. 验证清单（每次 rebase 后跑）

1. `ssh coding 'cd /mnt/coding/rikkahub-build && setsid nohup ./build.sh :app:assembleDebug > logs/build-$(date +%F-%H%M).log 2>&1 &'` → 出 APK
2. 装机后手动 smoke：
   - 让模型读一个 >2000 行的文件 → 返回带行号 + `[more → offset N]`，不带 offset 时仍是全文
   - 读一个 >16K 的输出（例如 shell 打日志）→ 出现 `ref`，`workspace_result` 能翻到第 2 页
   - `workspace_grep` 在 `app/src` 里搜一个高频词 → 命中数被截断且提示 omitted
   - `workspace_glob "*.kt"` 能列出深层路径
   - SSH 远程工作区上重复第 2 条（验证 bug 修复）
3. 回归：图片读取、8MB 上限、写文件/编辑/审批流程不变

## 6. 风险与回退

| 风险 | 处理 |
| --- | --- |
| 上游重写 `WorkspaceTools` / `ChatToolFactory` | 锚点字符串定位；三个 commit 独立，可只保留 1、2 丢 3 |
| proot/SSH 都没有 rg 且 grep 不支持 `-r --include` | fallback 到 `find + grep`，最坏退化成纯 grep（仍比让模型手拼强） |
| 模型滥用 `workspace_result`（疯狂翻页） | 默认 limit 64、单次 maxBytes 16K；提示里写"不要越过未显示内容" |
| 输出格式变化影响已有 prompt | 只在带参数/超预算时改变；默认路径字节级不变 |
| 预算变小让某些任务信息不够 | 常量集中在 `OpsBudget`，一个数改全局 |

## 7. 工作量

| Commit | 新增 | 改上游 | 估计 |
| --- | --- | --- | --- |
| 1 read 分页 | 0 | WorkspaceTools.kt ~45 行 | 1–2 h |
| 2 结果引用 + result 工具 | 2 个新文件 ~150 行 | ChatToolFactory ~12 行 | 2–3 h |
| 3 grep/glob | 1 个新文件 ~120 行 | ChatToolFactory 1 行 | 2–3 h |
| 编译/装机验证 | — | — | 1 h/轮 |

合计约 6–9 小时，三个可独立回退的 commit。读取去重见 §9（重新评估后确认可做，判据换成「同字节是否还在会话里」，作为 Commit 4，排在 1/2 之后做）。


## 8. 补充：截断降级到底怎么回事（2026-11-05 讲清楚）

链路是这样的：

1. `ChatToolFactory` 给每个工具套一层装饰器 `withOutputTruncation`，它只做一件事：输出超过 32K 字符时，把**全文写到手机侧** `filesDir/tool_outputs/<uuid>.txt`，只回 4K 预览 + 一句"用 shell cat 那个文件"。
2. 这个"cat 得到"的前提是：rootfs 里挂了 `/tool_outputs`。这个 bind mount 只在**本地 proot 工作区**存在（`di/RepositoryModule.kt:77-78`，source=`filesDir/tool_outputs`，target=`/tool_outputs`）。
3. 所以门槛写成了 `capabilities()?.rootfs == true`；而 `Workspace.kt:18` 里 SSH 工作区是 `rootfs=false`。
4. 结果是：**SSH 远程工作区根本不套这个装饰器** —— 不是"给你个读不到的文件"，而是**完全不截断**，几十万字符的 shell 输出原样进上下文，下一步又整段重发一遍。这比本地那条"截断+转存"的降级还糟；本地那条的问题只是每恢复一次要一次 shell 往返（工具串行，往返=全上下文重发）。

`workspace_result`（Commit 2）把 ref 改成**app 内文件 + 工具内翻页**，和 rootfs 无关 → 两种工作区都能用，顺手把这个门槛删掉。

## 9. Commit 4：读取去重（重新评估：能做，但要换个判据）

### 为什么不能照抄 ops
dsh-ops 的判据是「此前那次读取的正文**确实进了最近一次成功完成的普通 llm/stream 请求**」，靠的是宿主概念：session + `contentGeneration`（`surface.d.ts: applySurfacePlan`）+ 消息投影 + `tools/result` 冻结语义。RikkaHub 没有这些，照抄只能靠猜"应该还在上下文里"，一旦压缩把那条消息换了，模型就会指着一个不存在的内容编造。

### 能用的判据
**「同一段字节是否仍然出现在这条会话当前的消息列表里」** —— 只判断存在性。

好处：压缩、分支切换、删消息、编辑重发，全都会让判定自然失败；**失败方向永远是"多给一次全文"，不会出现"指向不存在的内容"**。

### 实现（无状态，不落台账）

read 工具执行时：
1. 先算出本次要返回的窗口正文 `text`
2. 取 `conversationRepository.getConversationById(conversationId)?.currentMessages`（`Conversation.kt:51`），扫所有 `UIMessagePart.Tool`
3. 找 `toolName == workspace_read_file`、参数相同（path/offset/limit）、且 output 里的 Text 与 `text` **先比长度再比 sha256** 相等的那个
4. 命中 → 返回存根：

```
[Notice: Unchanged range; identical bytes already returned in this conversation by tool call "abc123". Re-read from disk, content identical — reuse the earlier output.]
file /workspace/foo.md | lines 531-568 of 1204 of 24594 B
[body skipped — see tool call "abc123"]
```

5. 未命中 → 正常返回

### 覆盖范围与代价
- **跨轮重读**（模型过几轮又说"我再看一眼那个文件"）→ 覆盖，这是主战场。
- **同一次生成内的重复读** → 不覆盖：当前这轮的中间消息还在内存的 message queue 里，没落库。要覆盖得在 `ai/.../AgentLoop.runTool` 上加钩子（那里能拿到 `message`），属于动共享模块，第一轮不做。
- 成本：每次 read 扫一遍消息，只比 read 类 Tool part、先比长度，200 条消息上限保护，可忽略。
- 实现量：新文件 `OpsReadDedup.kt` ~80 行 + read 工具里 ~10 行 + `conversationId` 从 `ChatRequestFactory`（L108 附近）传进 `ChatToolFactory`（各 1 行）。
- 不做：不让它省 IO（文件该重读还得重读，只是不回灌正文），也不持久化（重启后不生效，安全）。

### 工作量补充
| Commit | 新增 | 改上游 | 估计 |
| --- | --- | --- | --- |
| 4 读取去重 | 1 个新文件 ~80 行 | read 工具 ~10 行 + 两处各 1 行传参 | 2–3 h |

顺带说清它省的是什么：**省的是输出 token 和上下文长度，不是磁盘 IO**。真要被大量使用，得靠它跟 Commit 1/2 配合（先分页，再对重复窗口去重）。

## 10. 验证结论（2026-11-05，在 .ops 实例上实测）

| 项 | 结果 | 备注 |
| --- | --- | --- |
| read 分页（小文件） | ✅ | `lines 100-263 of 30000` |
| read 分页（>8MB，14.6MB） | ✅ | 改成走 shell 切片后通过 |
| `cat` 大输出 → ref → `workspace_result` 翻页 | ✅ | `[stdout]` 已展开成真实多行 |
| ref 预览不再转义 | ✅ | 预览取自 `flattenForStore()` |
| grep 路径不存在报错 | ✅ | `path does not exist:`，不再谎报 no match |
| grep 单文件带路径前缀 | ✅ | `path:line: text` |
| glob | ✅ | |
| 去重命中（跨轮、同内容同区间） | ✅ | 回存根并指向首次的 toolCallId |
| 去重不误判（内容改过） | ✅ | 返回全文，判据钉在字节上 |
| 去重不误判（不同区间） | ✅ | 正常给正文 |

## 11. 实现细节（比方案更具体的几处，改代码前先看）

1. **>8MB 分页走 shell**：`readWindowViaShell()` —— 带 offset/limit 时不整文件读入内存，
   而是 `awk 'END{print NR}'` 拿总行数（`wc -l` 对无尾换行文件少一行）、`wc -c` 拿字节数、
   `sed -n 'a,bp'` 取区间、`cut -c 1-2000` 截长行，返回 `__OPS_META__ <总行> <字节>` + 正文。
2. **引用落盘前两道整形**：`flattenForStore()`（把 `{"exitCode":0,"stdout":"..."}` 这类工具信封展开成 `[stdout]` + 多行，
   只对像信封的对象生效，普通 JSON 文档不动）+ `wrapLongLines()`（单行 >2000 字符硬折成 400 一段，标 `[!wrapped i/n]`）。
3. **搜索用退出码信封**：管道里有 `head`，退出码会被换成 0，所以脚本把真实 rc 用 `__OPS_RC__=` 带回，
   Kotlin 侧区分 3=路径不存在 / 2=执行失败 / 1=无命中；busybox grep 没有 `--include`，glob 兜底在 Kotlin 侧用通配符过滤。
4. **预期内的错误不抛异常**：`error()` 会被 AgentLoop 包成「消息 + 10 行堆栈」进上下文（约 150 token 纯噪音），
   路径不存在/超时/ref 非法一律返回 `Text("error: ...")`；提前返回用显式标签 `return@opsGrep` 等。
5. **Kotlin 拼 shell 的坑**：字符串里 `"$tmp"` 是模板插值，必须 `"\$tmp"`；已引号的路径直接内联，减少中转变量。

## 11.5 实测发现：上游 shell 后端自己会在约 128KB 截断

`workspace_shell` 的输出是 JSON 信封，其中 `truncated` 字段会告诉调用方被截过。实测 `cat` 一个 165000 B / 5000 行的文件：
落到 ref 里只剩 **131129 B / 3976 行**，末行是 `[truncated] true`。

问题不在截断本身，而在**这一行原本落在第 3976 行**——模型翻两三页就下结论，会以为自己看的是全文。
已修（提交 `711fa81d`）：`flattenForStore` 改成标量前置，`[exitCode]`、`[timedOut]`、`[truncated]` 出现在**第一页开头**。

**工作流结论**：读大文件用 `workspace_read_file` + `offset/limit`（走 `sed`，不经过 shell，不截断）；
不要用 `cat` 去读超过 100KB 的文件——它必然被后端切掉尾巴。

## 12. 已知边界（不是 bug，是有意的取舍）

- **同一次生成内**的重复读不去重：当前这轮消息还没落库，判据不成立；跨轮才生效。
- `prune()` 保留最近 200 个引用，历史消息里的 ref 会变 `[expired]`，模型只能重跑工具。
- 多会话并发写同一目录没有加锁。
- 常驻多了 3 个 schema（`workspace_result` 常驻，`grep`/`glob` 有工作区时才有），每轮多几百 token。
- `workspace_grep` 的命中上限是「取回 400 行前 N 条」，不是全量计数；超了只报 `omitted`。

## 13. 跟上游 / 交付物（落盘索引）

| 东西 | 位置 |
| --- | --- |
| 上游基线 tag | `base/v3.1.1` = `903e058b`（本仓 coding 上） |
| 本次成果 tag | `ops/v3.1.1-ops` = `92b15251` |
| 补丁序列（10 个） | `/workspace/projects/rikkahub-ops/patches/0001..0010*.patch`（纯文本，不怕 .git 被吃） |
| 构建环境 | `/workspace/projects/rikkahub-ops/BUILD-ENV.md` |
| debug 包（`.ops`，与官方并存） | `/workspace/rikkahub-ops-arm64-debug.apk` |
| release 包（官方包名 `me.rerere.rikkahub`） | `/workspace/rikkahub-ops-release.apk` |

升级上游时的动作：

```bash
cd /mnt/coding/rikkahub-build/src/rikkahub
git fetch origin master --tags
git rebase --onto <新tag> base/v3.1.1 ops
./…/build.sh :app:assembleGithubDebug      # 先编译验证
git tag -f base/<新版本> <新tag> && git format-patch base/<新版本>..ops -o /workspace/.../patches
```
冲突大概率出现在 `WorkspaceTools.kt` / `ChatToolFactory.kt` 的 region 块之间；新逻辑全在新文件里，冲突多为上下文漂移。
