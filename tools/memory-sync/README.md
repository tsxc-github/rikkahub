# Memory Sync helper / RPC v1

Python **3.9+**, POSIX/Linux, standard library only. This is an SSH-invoked one-shot
helper, not a daemon or HTTP service. It does not install itself, open ports, change
SSH settings, deploy an APK, or touch an existing memory library automatically.

## Install explicitly

On the chosen SSH server, as the account already used by the App's approved SSH
workspace, copy **only `memory_sync.py`** to:

```text
$HOME/.local/share/rikkahub-memory-sync/memory_sync.py
```

For example, execute these commands **yourself on that server**, from a checked-out
copy of this repository:

```sh
install -d -m 700 "$HOME/.local/share/rikkahub-memory-sync"
install -m 600 tools/memory-sync/memory_sync.py \
  "$HOME/.local/share/rikkahub-memory-sync/memory_sync.py"
```

No executable bit or PATH entry is needed. The App's fixed invocation is:

```sh
python3 "$HOME/.local/share/rikkahub-memory-sync/memory_sync.py" rpc --root '/absolute/private/data-root'
```

Choose a new, private **absolute data root outside workspace/rsync mirrors**. Root
and all its ancestors must be real directories, not symlinks; use canonical paths
(no `..`, `.` or trailing slash). `create` creates missing root directories mode
0700. Managed files are mode 0600. Keep the root on one local POSIX filesystem with
working `flock`, atomic rename and directory `fsync`; network filesystems with
weaker durability are not supported. Protect the root from other users and back it
up as a unit while helper clients are stopped. Do not copy just `memories/` as a
live synchronized library: that loses generations, tombstones and receipts.

Create a fresh random space UUID locally (`python3 -c 'import uuid; print(uuid.uuid4())'`)
and choose it explicitly in the App, or create it with this CLI:

```sh
HELPER="$HOME/.local/share/rikkahub-memory-sync/memory_sync.py"
ROOT=/absolute/private/data-root
SPACE=01234567-89ab-4cde-8123-456789abcdef
python3 "$HELPER" create --root "$ROOT" --space "$SPACE"
python3 "$HELPER" status --root "$ROOT" --space "$SPACE"
```

An existing space is **never overwritten by create**. The account's authentication
and host-key approval come from the existing SSH workspace, not helper credentials.
The helper itself is transport-agnostic and never launches a shell or subprocess.

## Agent editing: checkout → edit scratch → commit

```sh
python3 "$HELPER" checkout --root "$ROOT" --space "$SPACE" \
  --path /topics/servers.md --draft /absolute/scratch/servers.md
# Edit /absolute/scratch/servers.md with the normal agent editor.
python3 "$HELPER" commit --root "$ROOT" --space "$SPACE" \
  --draft /absolute/scratch/servers.md
```

Checkout exclusively creates the scratch Markdown and its private sidecar
`servers.md.memory-sync.json`; it refuses existing files and does not overwrite
an earlier draft. It also retains a sibling `.memory-sync.lock` for per-draft
process serialization. Keep the sidecar with the draft: it holds its **original
baseline generation**, root/space/path, and a persisted pending UUID/content digest.
Neither checkout nor commit adds frontmatter or alters Markdown/CRLF. Checkout of
an absent/tombstoned document creates an empty draft but retains its absence token.

Commit **never fetches the latest token to cover an old draft**. Concurrent edits
return `conflict`, keeping both draft and original baseline. To merge a conflict,
checkout the latest document to a **different** scratch name, merge intentionally,
then commit that new draft. Do not hand-edit a sidecar token. A lost reply or crashed
commit is recovered by rerunning the same command: commit first checks its pending
UUID, adopts only that UUID's original result, or retries the identical request.
If that operation is confirmed uncommitted and the draft changed, the changed draft
gets a new UUID but retains the original checkout token. No uncommitted draft is
automatically rebased onto someone else's version.

A checkout interrupted between the two exclusive file creations can leave an
incomplete scratch pair; inspect it and use a fresh scratch filename. It cannot
publish anything to the server. Scratch directories must already exist, be outside
the managed root, and have no symlink ancestors. The CLI never writes directly into
`memories/` on behalf of an editor.

Deletion requires an explicitly retained baseline token and stable operation UUID:

```sh
python3 "$HELPER" delete --root "$ROOT" --space "$SPACE" \
  --path /topics/servers.md --token 7 \
  --operation-id a1234567-89ab-4cde-8123-456789abcdef
```

Retry a lost deletion reply with **exactly the same arguments/UUID**. The token
must be from the version the agent actually read, not a new status/read query made
at deletion time. `status`, checkout, commit and delete print small JSON summaries
without Markdown bodies, including on conflict. For **non-RPC CLI commands**, exit
0 means success, exit 1 means a protocol/storage/draft error; argparse errors exit 2.

Raw reading is allowed at:

```text
ROOT/spaces/SPACE/memories/<virtual-path-without-leading-slash>
```

**Do not edit, remove, rename, add files or symlinks there.** Use scratch + commit.
Do not edit `state/`, `recovery/`, `locks/`, receipts or tombstones either.

## RPC v1 wire contract

`rpc --root ABSOLUTE_ROOT` reads exactly **one UTF-8 newline-terminated JSON frame
from stdin**, requires EOF after it, emits exactly one JSON line on stdout, and
exits **0 for every valid JSON envelope, including errors**. Read `ok`/`error.code`,
not the exit status, to distinguish RPC success and conflict. A crash/no envelope
is a transport failure. Close SSH command stdin after sending the request; do not keep it open as
an interactive session. All request fields and Markdown are JSON data, never shell
arguments. Only the fixed helper command and safely quoted root belong in the SSH
command. Do not print requests, responses or raw SSH streams to application logs.

Every request has:

```json
{"protocol":1,"op":"list","spaceId":"01234567-89ab-4cde-8123-456789abcdef"}
```

UUIDs must be canonical lowercase hyphenated strings. Unknown fields, duplicate
JSON keys, invalid UTF-8, non-finite numbers, multiple frames and unsupported
protocols/operations fail explicitly. Token values are canonical nonnegative decimal
**strings**, not hashes, timestamps or JSON numbers. An untracked absence reads as
`{"path":"/name.md","token":"0","content":null}`.

| op | Additional request fields | Success payload |
| --- | --- | --- |
| `create` | none | `spaceId` (existing → `already_exists`) |
| `list` | none | `documents`: coherent full active **and tombstone** array, each with `path`, `token`, `content` |
| `read` | `path`, optional `expectedToken` | `document` (absence is a document with null content) |
| `put` | `path`, `expectedToken`, `operationId`, `content` string | `document` original committed result |
| `delete` | `path`, `expectedToken`, `operationId` | `document` retained tombstone |
| `operationStatus` | `operationId` | `found`; if true, `document` original committed result |
| `status` | none | `spaceId`, active `documents` count, `tombstones`, active `bytes`, `receipts`, `driftRecords` |

Success: `{"protocol":1,"ok":true,...}`.
Error: `{"protocol":1,"ok":false,"error":{"code":"conflict","message":"Generation changed"},"document":{...}}`.
Error codes: `conflict`, `invalid`, `drift`, `not_found`, `already_exists`,
`permission`, `unsupported`. A CAS/read-token conflict includes the coherent current
document; if that document would exceed the response budget, the entire response
is instead an `invalid` budget error, never a truncated conflict document. No
Markdown, request strings or OS exception text are written to diagnostics.

Example stdin frame (generated using a JSON encoder, not string interpolation):

```json
{"protocol":1,"op":"put","spaceId":"01234567-89ab-4cde-8123-456789abcdef","path":"/profile.md","expectedToken":"0","operationId":"a1234567-89ab-4cde-8123-456789abcdef","content":"---\r\ntitle: Profile\r\n---\r\nOriginal Markdown\r\n"}
```

Unknown `operationStatus` permits retrying the **same UUID and full request**.
A committed UUID is permanently bound to the SHA-256 digest of its canonical full
request; a different path/content/token/op with that UUID is `invalid`. Repeating
it returns the **original** result, even after later writes. Receipts are scoped
to their space. Equal-content writes require a matching CAS, receive a durable
receipt, and do not advance an existing generation. Deleting a never-existing path
creates generation 1 absence; tombstones and receipts are never garbage-collected.
Deletion/recreation increments generations, preventing ABA stale-token acceptance.

### Validation budgets

- Request including newline: **512 KiB**. Response including newline: **128 MiB**.
  This accommodates worst-case JSON escaping of a valid 48 KiB new document.
  Escaping still counts toward wire limits for larger legacy drafts.
- Active library: **2,000 documents**, **64 MiB** total UTF-8 Markdown bytes.
- New or growing document: **48 KiB**. An existing legacy over-limit document can
  be listed/read/exported unchanged, or committed unchanged/shrinking within the
  wire/aggregate budgets. Nothing is silently truncated.
- Paths: absolute, `.md` suffix, at most **128 UTF-16 code units** (same as Kotlin);
  no empty/blank/trim-changing segments, dot-prefixed segments, backslashes,
  ISO-control characters, traversal, symlinks or hardlinks. File/directory and
  Unicode `casefold()` collisions (including directory prefixes and retained
  tombstone identities) are rejected.
- Reads validate the full active space and fail atomically, never returning an
  incomplete list that could be mistaken for deletions. Retained metadata and
  individual receipt/journal files also have a 128 MiB storage-read ceiling;
  writes reject growth past that ceiling before publication.

### Durability and drift

Layout:

```text
ROOT/locks/SPACE.lock
ROOT/spaces/SPACE/memories/...                 # exact raw UTF-8 Markdown
ROOT/spaces/SPACE/state/index.json            # generations, hashes, lengths, tombstones
ROOT/spaces/SPACE/state/receipts/OPERATION.json
ROOT/spaces/SPACE/recovery/journal.json        # only while transaction is in flight
ROOT/spaces/SPACE/recovery/drift/DIGEST.json   # private observed-drift evidence
```

Each operation takes a **per-space interprocess flock**, recovers a durable journal,
then validates all tracked files and unexpected raw additions before responding.
A write fsyncs a journal containing old/new state, old/new body and full receipt,
then fsyncs/atomically replaces Markdown, state and receipt, fsyncs the corresponding
directories, removes the journal and fsyncs its directory before replying. The old
body is base64 inside the journal to bound JSON escaping of a large legacy file.
Recovery rolls a durable intent forward and tolerates each old/new intermediate
state; the journal stays until the result and receipt are durable. An unpublished
journal temporary is not a commit and may be retried with the same UUID. Private
metadata `.tmp-*` or unpublished `.creating-*` leftovers may survive a process
crash; they are not protocol state. A staged empty create is validated/reused and
published by one directory rename under the same space lock.

A mismatching, missing, untracked, unsafe or unexpectedly changed recovery file
returns `drift`, **does not overwrite the observed original**, and pauses the space.
For regular raw files within the 64 MiB read budget, drift evidence saves exact
bytes as base64 (even invalid UTF-8), expected metadata and path, fsynced mode 0600.
Unsafe links/special files and oversized raw files are never followed/copied;
their evidence records the kind/path and leaves the original in place. No drift
content goes into stdout errors or stderr. The helper never treats a raw addition
as token 0 data or a raw disappearance as a protocol deletion.

Stop uncooperative writers, preserve the entire root, and inspect private drift
records before repair. Restore the tracked original bytes (or a journal-known
old/new version for interrupted commits); rerun status/operationStatus to recover.
An untracked raw file should be preserved in scratch and removed from the managed
view intentionally, then introduced with checkout/commit. Do not reset generations
or delete journals/tombstones/receipts to force acceptance. Historical evidence
remains after repair (`driftRecords` is an audit count, not an active-error flag).
There is no force-overwrite or automatic selection of one side. Lost originals
require deliberate operator recovery from backups; the helper does not fabricate
them from hashes.

Locks coordinate conforming helper clients only. No tool can guarantee detection
before overwrite if another same-account process races arbitrary filesystem writes
or tampers with private protocol metadata. Exclusive ownership of the managed root
is an operational requirement, not a claim that flock constrains other editors.

## Tests (isolated fixtures only)

From this repository on POSIX/Linux:

```sh
python3 -m unittest discover -s tools/memory-sync -p 'test_memory_sync.py' -v
```

The tests use only `TemporaryDirectory` fixtures and the current Python executable.
They launch real independent helper processes for CAS/UUID contention, crash each
write stage, recover new/replace/delete operations, check original receipt results,
ABA, Markdown fidelity, CLI old-draft conflicts, durable pending UUID retries,
traversal/symlink/hardlink/casefold safety, out-of-band drift and protocol/aggregate/
response budgets. Large budget fixtures require temporary disk and memory (not
user data). No SSH, App data, ADB, deployment or machine configuration is accessed.
The separate PowerShell test runner is documented in `docs/memory-sync/TESTING.md`.

`MEMORY_SYNC_TEST_FAILPOINT` is **test-only opt-in**; never set it for a real root.
The module's `FAILPOINTS` tuple lists the exact stages. A match terminates the real
process with exit 86 and no reply, using `os._exit`, not a mock/fake storage layer.
Delete has no document-temporary stage. Tests remove inherited failpoint settings
and set one explicitly per crash subprocess; later recovery runs have none set.
Process-crash tests verify recovery ordering, not physical power-loss behavior on
all storage hardware.
