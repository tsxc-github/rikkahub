# 装机 smoke 清单（10 条）

装完新包后按顺序跑。**造测试文件用 `awk`，不要用 `seq`** —— busybox 的 `seq` 没有 `-f`。

```sh
mkdir -p /workspace/tmp
awk 'BEGIN{for(i=1;i<=5000;i++) printf "line %06d xxxxxxxxxxxxxxxxxxxx\n", i}' > /workspace/tmp/big.txt      # ~130KB
awk 'BEGIN{for(i=1;i<=600000;i++) printf "line %06d xxxxxxxxxxxxxxxxxxxx\n", i}' > /workspace/tmp/huge.txt    # ~14MB，测 >8MB
awk 'BEGIN{for(i=1;i<=400;i++) printf "safety row %03d padding padding padding\n", i}' > /workspace/tmp/safety.txt
```

| # | 怎么做 | 预期 |
| --- | --- | --- |
| 1 | `workspace_read_file(path=/workspace/tmp/big.txt, offset=498, limit=3)` | `file … | lines 498-500 of 5000 | …` + 行号 + `[more: … → offset 501]` |
| 2 | `workspace_read_file(path=/workspace/tmp/huge.txt, offset=100000, limit=4)` | 同上，**不能**报 `File is too large to read` |
| 3 | `workspace_shell(command="cat /workspace/tmp/big.txt")` | 返回 `[Tool output stored: N chars]` + `ref:` + 预览（预览应是 `[stdout]` 开头，不是 `{"exitCode":0,…`） |
| 4 | `workspace_result(ref=<上一步的 ref>)` | `file tool_outputs/<ref>.txt | lines 1-…` + 真实多行，不是一行 5 万字符 |
| 5 | `workspace_result(ref=<同 ref>, offset=65)` | 翻到第二页，行号接着上一页 |
| 6 | `workspace_grep(pattern="line 000123", path=/workspace/tmp/big.txt)` | `…/big.txt:123:line 000123 …`，带文件名 |
| 7 | `workspace_grep(pattern="x", path=/workspace/tmp)` | `[hits: 80 shown / N matched; … omitted]`（命中上限起作用） |
| 8 | `workspace_grep(pattern="foo", path=/workspace/nope)` | **`error: path does not exist:`**，不能是 `no match`；也不能带 Kotlin 堆栈 |
| 9 | `workspace_glob(pattern="*.txt", path=/workspace/tmp)` | `files: N under /workspace/tmp (pattern *.txt)` |
| 10 | 去重：先读 `safety.txt` 1-222，**等一轮回复**，再读同样区间 | 第二次回 `[Notice: Unchanged; identical bytes …]`；把第 100 行改掉再读则回全文 |

## 三条容易误判的

- **第 10 条必须跨轮**：同一次生成内的两次读不会命中（那轮消息还没落库）。这是设计边界，不是 bug。
- 第 8 条的报错**不应该带堆栈**；带堆栈说明 `error()` 又回来了，去看 `OpsSearchTools.kt` 里的 `return@opsGrep`。
- 第 5 条翻页如果报 `[expired: ref …]`，是 `prune()` 把超过 200 个的老引用清了，重跑第 3 步即可。
- 第 3 条用 `cat` 读 >100KB 的文件时，ref 里行数会**少于源文件行数**，第一页应能看到 `[truncated] true`。
  这是上游 shell 后端 ~128KB 的硬截断，不是我们的 bug；要完整内容请改用 `workspace_read_file` + `offset/limit`。

## 每轮改完代码后

```bash
ssh coding 'cd /mnt/coding/rikkahub-build && setsid nohup ./build.sh :app:assembleGithubDebug > logs/build.log 2>&1 &'
ssh coding 'grep -E "BUILD SUCCESSFUL|BUILD FAILED|^e: " /mnt/coding/rikkahub-build/logs/build.log'
```
编译过再验 dex 里有没有新符号（`unzip -p apk classes*.dex | grep -ac "<新字符串>"`），然后才下包。只看 BUILD SUCCESSFUL 不看符号，会被增量构建骗。
