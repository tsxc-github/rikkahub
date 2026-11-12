# ops-v2 修复版实机实测（2026-11-06，a4341901 release）

测试者：本会话的 AI 自身，工作区 `/workspace`（本地 proot）。测试方式为**直接调用自己这一轮真实的 v2 工具**（工具描述已是 v2：读取工具声明 Markdown 512 KiB/paged/cursor），属于端到端实测；不是模拟。

测试文件：`/workspace/tmp/v2test/`（long.txt 20009B 单行；normal.txt 32400B/1200行；doc.md 34692B；big.md 502893B；many.txt 30行命中）。

## 结论

**用户报告的“二次截断丢 cursor / ref 套 ref”已修复**，其余核心能力正常。发现 1 个可优化点（非缺陷）：**读取页被历史预算折叠后，要取回续读 cursor 需 2 次 `workspace_result`**，因为 cursor 位于快照末行。

## 逐项结果

| # | 用例 | 期望 | 实测 |
|---|---|---|---|
| 1 | 20 KB 单行默认读 | 6 KiB 页 + 行内 cursor，不得变 ref | ✅ 6144B + `[next cursor]`，同一 source line 续读 |
| 2 | cursor 连续读到尾 | 逐字节拼接 | ✅ 6144×3+1577=20009B，末页 `complete`，以 `TAIL_END` 结束 |
| 3 | 34.7 KB Markdown 默认读 | 全文、complete、无 cursor | ✅ 全量返回，无续页、无 ref |
| 4 | Markdown 显式 offset/limit | 走普通窗口 | ✅ `source line 1` + 3 行 + cursor |
| 5 | 普通文件 offset/limit | 行号 + cursor | ✅ `source line 1180–1184`，cursor l=1185 |
| 6 | 单文件 30 命中 | 全给，无隐藏每文件 8 条 | ✅ `shown=30 complete` |
| 7 | `max=5` | limited + 提示 | ✅ `status=limited` + `[more exist]` |
| 8 | 不存在路径 | error 而非 no match | ✅ `status=error detail="path does not exist"` |
| 9 | `glob=**/*.kt` | 明确拒绝 | ✅ `Unsupported glob` |
| 10 | `filesOnly` | 只回路径 | ✅ 仅 `/workspace/tmp/v2test/many.txt` |
| 11 | `glob=*.md` 过滤 | 命中含 .md | ✅ 11 条，`shown=11` |
| 12 | shell 502 KB 输出 | `source_truncated` + ref | ✅ 头行 `source_truncated=true`，给 ref |
| 13 | ref 翻页 | snapshot 行号、无 ref 嵌套 | ✅ `capture_status=source_truncated`、`snapshot line 1`、正文完整；续页 `snapshot line 65` |
| 14 | cursor+offset 同时传 | 报错 | ✅ `Use cursor OR offset/limit` |
| 15 | 乱写 cursor | 报错 | ✅ `Invalid cursor`（抄错一段即被拒） |
| 16 | 归档页 → ref → 续读 | 能回到原文件继续 | ✅ 2 页后取回**原始文件 cursor**，与先前 page2 cursor 完全一致 |
| 17 | 历史预算折叠 | 旧页变回执 | ✅ 会话累积约 45 KB 后，早期读取页显示 `[archived … ref=…]`，UI 原文未受影响 |

## 发现的可优化点（不是这次要修的缺陷）

读取页被折叠时，回执只有 `ref`。要拿回**原文件的续读 cursor**，得 `workspace_result` 翻到快照末行——实测 2 次调用（一次 6 KB 正文 + 一次短尾页）。

建议（未实施）：把续读指针写进归档回执，例如

```
[archived "/path"; status=limited; ref=…; resume=cursor…]
```

这样能直接 `workspace_read_file(cursor=…)` 继续，省掉恢复调用。代价是每条回执多约 300 B；只在“分页未读完”的回执上加即可。

## 校准数据：32 KiB 历史预算偏紧

本次测试会话语义上只有 ~7 个工具结果（3 页长行 + 1 个 Markdown 全读 34.7 KB + 1 个 shell 预览 4 KB + 2 个 ref 页）就触发了折叠。因为 Markdown 豁免、不计入，所以纯普通正文到 32 KB 很快。

这对“读代码会话”意味着旧结果会较快变成回执。**可选调整**：`OpsBudget.HISTORY` 32 KiB → 48/64 KiB。这是取舍：更大 = 少恢复调用、多输入 token。建议等真实任务 usage 数据再定，不先改。

## 未测项（不可当作通过）

1. **Markdown >512 KiB 分支**：本次 `big.md` 只有 491 KiB（502893B），未越过 512 KiB；**故意没有**在会话里触发，否则会把 512 KB 灌进上下文，与本目标相悖。该分支由 JVM 回归 `Markdown over large budget remains precisely resumable` 覆盖，用户可自行用 >524288B 的 .md 触发验证。
2. **SSH 远程工作区**的范围读取：当前工作区是本地 proot，未配置 SSH 工作区，故未验证 SFTP 偏移读。
3. **累计 token / 缓存命中收益**：未做同任务对比基准，仍无节省百分比结论。
4. 图片读取、写文件/编辑、审批、压缩请求路径未逐一复测（本次改动未触碰这些分支）。

## 复现方式

测试文件保留在 `/workspace/tmp/v2test/`，可直接用同样参数重跑；`big.md` 可用 `awk 'BEGIN{for(i=1;i<=11000;i++)printf "## big %d 内容内容内容内容\nbody\n",i}' > bigger.md` 造出 >512 KiB 版本。
