"""Isolated, real-process RPC/CLI/crash tests. Never uses configured/user data."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

HELPER = Path(__file__).with_name("memory_sync.py")
SPEC = importlib.util.spec_from_file_location("memory_sync", HELPER)
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


def frame(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"


class MemorySyncTests(unittest.TestCase):
    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix="rikkahub-memory-sync-test-")
        self.addCleanup(self.fixture.cleanup)
        self.base = Path(self.fixture.name)
        self.root = self.base / "data"
        self.space_id = str(uuid.uuid4())
        self.assert_ok(self.call("create"))

    def request(self, op, **fields):
        return {"protocol": 1, "op": op, "spaceId": self.space_id, **fields}

    def start(self, request, failpoint=None):
        environment = dict(os.environ)
        environment.pop("MEMORY_SYNC_TEST_FAILPOINT", None)
        if failpoint:
            environment["MEMORY_SYNC_TEST_FAILPOINT"] = failpoint
        process = subprocess.Popen([sys.executable, str(HELPER), "rpc", "--root", str(self.root)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env=environment)
        return process, frame(request)

    def finish(self, process, data):
        stdout, stderr = process.communicate(data, timeout=30)
        self.assertEqual(stderr, b"", "Helper must not leak request/content to diagnostics")
        self.assertEqual(len(stdout.splitlines()), 1, stdout[:200])
        result = json.loads(stdout)
        self.assertEqual(result["protocol"], 1)
        self.assertEqual(process.returncode, 0, "Valid RPC envelopes must not look like SSH IO failure")
        return result

    def call(self, op, **fields):
        return self.finish(*self.start(self.request(op, **fields)))

    def assert_ok(self, result):
        self.assertTrue(result["ok"], result)
        return result

    def assert_error(self, result, code):
        self.assertFalse(result["ok"], result)
        self.assertEqual(result["error"]["code"], code, result)
        return result

    def put(self, content, expected="0", path="/profile.md", operation_id=None):
        return self.call("put", path=path, expectedToken=expected, content=content,
                         operationId=operation_id or str(uuid.uuid4()))

    @property
    def space(self):
        return self.root / "spaces" / self.space_id

    @property
    def raw_file(self):
        return self.space / "memories" / "profile.md"

    def seed(self, documents):
        """Test-only migration/large-space fixture, entirely under TemporaryDirectory."""
        index = {}
        for path, content, generation in documents:
            data = content.encode("utf-8") if content is not None else None
            index[path] = {"token": str(generation), "bytes": len(data) if data is not None else 0,
                           "sha256": hashlib.sha256(data).hexdigest() if data is not None else None}
            if data is not None:
                target = self.space / "memories" / path[1:]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        (self.space / "state" / "index.json").write_bytes(frame({"format": 1, "documents": index}))

    def test_create_list_read_delete_and_raw_markdown(self):
        self.assertEqual(self.call("list")["documents"], [])
        absent = self.call("read", path="/profile.md")["document"]
        self.assertEqual(absent, {"path": "/profile.md", "token": "0", "content": None})
        raw = "---\r\ntitle: 原文\r\nunknown: [1, 2]\r\n---\r\n[stated] 🦊\r\n"
        doc = self.assert_ok(self.put(raw))["document"]
        self.assertEqual(doc["token"], "1")
        self.assertEqual(self.raw_file.read_bytes(), raw.encode("utf-8"))
        self.assertEqual(self.call("list")["documents"], [doc])
        self.assertEqual(self.call("read", path="/profile.md", expectedToken="1")["document"], doc)
        self.assert_error(self.call("read", path="/profile.md", expectedToken="0"), "conflict")
        deleted = self.assert_ok(self.call("delete", path="/profile.md", expectedToken="1",
                                           operationId=str(uuid.uuid4())))["document"]
        self.assertEqual(deleted, {"path": "/profile.md", "token": "2", "content": None})
        self.assertFalse(self.raw_file.exists())
        self.assertEqual(self.call("list")["documents"], [deleted])
        status = self.assert_ok(self.call("status"))
        self.assertEqual((status["documents"], status["tombstones"], status["bytes"]), (0, 1, 0))
        self.assert_error(self.call("create"), "already_exists")

    def test_aba_and_noop_cas(self):
        first = self.assert_ok(self.put("same"))["document"]
        noop = self.assert_ok(self.put("same", "1"))["document"]
        self.assertEqual(noop, first)
        self.assert_ok(self.call("delete", path="/profile.md", expectedToken="1", operationId=str(uuid.uuid4())))
        recreated = self.assert_ok(self.put("same", "2"))["document"]
        self.assertEqual(recreated["token"], "3")
        conflict = self.assert_error(self.put("stale", "1"), "conflict")
        self.assertEqual(conflict["document"], recreated)
        self.assert_error(self.put("same", "1"), "conflict")

    def test_receipts_bind_full_request_and_retain_original_result(self):
        operation_id = str(uuid.uuid4())
        original = self.assert_ok(self.put("first", operation_id=operation_id))["document"]
        self.assertEqual(self.assert_ok(self.put("first", operation_id=operation_id))["document"], original)
        self.assert_ok(self.put("second", "1"))
        self.assertEqual(self.assert_ok(self.put("first", operation_id=operation_id))["document"], original)
        self.assertEqual(self.call("operationStatus", operationId=operation_id)["document"], original)
        self.assertFalse(self.call("operationStatus", operationId=str(uuid.uuid4()))["found"])
        for fields in ({"content": "changed"}, {"expectedToken": "2"}, {"path": "/else.md"}, {"op": "delete"}):
            request = self.request("put", path="/profile.md", expectedToken="0", content="first", operationId=operation_id)
            request.update(fields)
            if request["op"] == "delete":
                request.pop("content")
            self.assert_error(self.finish(*self.start(request)), "invalid")
        tombstone_id = str(uuid.uuid4())
        removed = self.assert_ok(self.call("delete", path="/profile.md", expectedToken="2", operationId=tombstone_id))
        self.assertEqual(self.call("operationStatus", operationId=tombstone_id)["document"], removed["document"])

    def test_concurrent_process_cas_has_exactly_one_winner(self):
        processes = [self.start(self.request("put", path="/profile.md", expectedToken="0",
                                            content="writer-" + str(i), operationId=str(uuid.uuid4())))
                     for i in range(12)]
        # Send all frames before waiting: real independent flock contenders, not mocked threads.
        for process, data in processes:
            process.stdin.write(data)
            process.stdin.close()
            process.stdin = None
        results = [self.finish(process, None) for process, _ in processes]
        self.assertEqual(sum(result["ok"] for result in results), 1)
        for result in results:
            if not result["ok"]:
                self.assert_error(result, "conflict")
        current = self.call("read", path="/profile.md")["document"]
        self.assertEqual(current["token"], "1")
        self.assertEqual(self.raw_file.read_bytes(), current["content"].encode("utf-8"))

    def test_concurrent_duplicate_uuid_commits_once(self):
        request = self.request("put", path="/profile.md", expectedToken="0", content="one result",
                               operationId=str(uuid.uuid4()))
        processes = [self.start(request) for _ in range(8)]
        for process, data in processes:
            process.stdin.write(data)
            process.stdin.close()
            process.stdin = None
        results = [self.assert_ok(self.finish(process, None)) for process, _ in processes]
        self.assertTrue(all(result == results[0] for result in results))
        self.assertEqual(self.call("status")["receipts"], 1)

    def test_each_durable_stage_recovers_put_new_put_and_delete(self):
        original_root, original_id = self.root, self.space_id
        for scenario in ("new", "replace", "delete"):
            stages = helper.FAILPOINTS
            if scenario == "delete":
                stages = [stage for stage in stages if stage != "document_temp_fsynced"]
            for stage in stages:
                with self.subTest(scenario=scenario, stage=stage):
                    self.root = self.base / (scenario + "-" + stage)
                    self.space_id = str(uuid.uuid4())
                    self.assert_ok(self.call("create"))
                    if scenario != "new":
                        self.assert_ok(self.put("old\r\n原文"))
                    op = "delete" if scenario == "delete" else "put"
                    path = "/topics/nested/profile.md" if scenario == "new" else "/profile.md"
                    request = self.request(op, path=path, expectedToken="0" if scenario == "new" else "1",
                                           operationId=str(uuid.uuid4()))
                    if op == "put":
                        request["content"] = "new\r\n🦊"
                    process, data = self.start(request, stage)
                    stdout, stderr = process.communicate(data, timeout=30)
                    self.assertEqual(process.returncode, 86)
                    self.assertEqual((stdout, stderr), (b"", b""))
                    found = self.assert_ok(self.call("operationStatus", operationId=request["operationId"]))
                    self.assertEqual(found["found"], stage != "journal_temp_fsynced")
                    retried = self.assert_ok(self.finish(*self.start(request)))["document"]
                    expected_content = request.get("content")
                    self.assertEqual(retried["content"], expected_content)
                    self.assertEqual(retried["token"], "1" if scenario == "new" else "2")
                    self.assertEqual(self.call("read", path=path)["document"], retried)
                    self.assertEqual(self.call("list")["documents"], [retried])
                    self.assertFalse((self.space / "recovery" / "journal.json").exists())
                    self.assertFalse(any(p.name.startswith(".tmp-") for p in (self.space / "memories").rglob("*")))
                    self.assertEqual(self.call("operationStatus", operationId=request["operationId"])["document"], retried)
        self.root, self.space_id = original_root, original_id

    def test_recovery_does_not_overwrite_out_of_band_edit(self):
        self.assert_ok(self.put("old"))
        request = self.request("put", path="/profile.md", expectedToken="1", content="new", operationId=str(uuid.uuid4()))
        process, data = self.start(request, "document_replaced")
        process.communicate(data, timeout=30)
        self.assertEqual(process.returncode, 86)
        self.raw_file.write_bytes(b"out-of-band private data")
        self.assert_error(self.call("list"), "drift")
        self.assertEqual(self.raw_file.read_bytes(), b"out-of-band private data")
        self.assertTrue((self.space / "recovery" / "journal.json").exists())
        # Explicit operator restoration to a journal-known version permits roll-forward.
        self.raw_file.write_bytes(b"old")
        self.assertEqual(self.call("operationStatus", operationId=request["operationId"])["document"]["content"], "new")

    def test_raw_drift_preserves_evidence_and_rejects_all_writes(self):
        self.assert_ok(self.put("original"))
        altered = b"private\r\nraw\xff"
        self.raw_file.write_bytes(altered)
        self.assert_error(self.put("must not overwrite", "1"), "drift")
        self.assertEqual(self.raw_file.read_bytes(), altered)
        records = list((self.space / "recovery" / "drift").glob("*.json"))
        self.assertEqual(len(records), 1)
        evidence = json.loads(records[0].read_bytes())
        self.assertEqual(base64.b64decode(evidence["observedBase64"]), altered)
        self.assert_error(self.put("other", path="/unrelated.md"), "drift")
        self.assertEqual(len(list((self.space / "recovery" / "drift").glob("*.json"))), 1)
        self.raw_file.write_bytes(b"original")
        self.assert_ok(self.call("list"))
        self.raw_file.unlink()
        self.assert_error(self.call("read", path="/profile.md"), "drift")

    def test_untracked_file_and_helper_looking_raw_file_are_not_deleted(self):
        untracked = self.space / "memories" / (".tmp-" + str(uuid.uuid4()))
        untracked.write_bytes(b"external private content")
        self.assert_error(self.call("list"), "drift")
        self.assertEqual(untracked.read_bytes(), b"external private content")
        untracked.unlink()
        untracked = self.space / "memories" / "untracked.md"
        untracked.write_bytes(b"unknown")
        self.assert_error(self.put("new"), "drift")
        self.assertEqual(untracked.read_bytes(), b"unknown")

    def test_symlink_hardlink_traversal_and_casefold_collisions(self):
        outside = self.base / "untouched.md"
        outside.write_bytes(b"do not touch")
        self.raw_file.symlink_to(outside)
        self.assert_error(self.put("overwrite"), "drift")
        self.assertEqual(outside.read_bytes(), b"do not touch")
        self.raw_file.unlink()
        os.link(outside, self.raw_file)
        self.assert_error(self.call("list"), "drift")
        self.raw_file.unlink()
        (self.space / "memories" / "unsafe").symlink_to(self.base, target_is_directory=True)
        self.assert_error(self.put("overwrite", path="/unsafe/untouched.md"), "drift")
        (self.space / "memories" / "unsafe").unlink()
        for path in ("relative.md", "/../escape.md", "/a/../b.md", "/a//b.md", "/.hidden.md",
                     "/a\\b.md", "/a\nb.md", "/a\x7fb.md", "/a\x85b.md", "/a.MD", "/a /b.md",
                     "/" + "🦊" * 63 + ".md"):
            with self.subTest(path=repr(path)):
                self.assert_error(self.put("content", path=path), "invalid")
        self.assert_ok(self.put("unicode", path="/Straße/Name.md"))
        self.assert_error(self.put("other", path="/STRASSE/other.md"), "invalid")
        self.assert_error(self.put("other", path="/Straße/name.md"), "invalid")
        self.assert_ok(self.put("file", path="/directory.md"))
        self.assert_error(self.put("other", path="/directory.md/child.md"), "invalid")
        # Tombstone paths continue reserving their casefold identity.
        self.assert_ok(self.call("delete", path="/directory.md", expectedToken="1", operationId=str(uuid.uuid4())))
        self.assert_error(self.put("other", path="/Directory.md"), "invalid")

    def test_root_metadata_and_lock_symlinks_are_rejected(self):
        link = self.base / "root-link"
        link.symlink_to(self.root, target_is_directory=True)
        original_root = self.root
        self.root = link
        self.assert_error(self.call("status"), "permission")
        self.root = original_root
        lock = self.root / "locks" / (self.space_id + ".lock")
        lock.unlink()
        target = self.base / "lock-target"
        target.write_bytes(b"untouched")
        lock.symlink_to(target)
        self.assert_error(self.call("list"), "permission")
        self.assertEqual(target.read_bytes(), b"untouched")
        lock.unlink()
        index = self.space / "state" / "index.json"
        index.unlink()
        index.symlink_to(target)
        self.assert_error(self.call("list"), "permission")
        self.assertEqual(target.read_bytes(), b"untouched")

    def test_per_document_and_legacy_shrink_budgets(self):
        self.assert_ok(self.put("a" * helper.MAX_DOCUMENT_BYTES))
        self.assert_error(self.put("b" * (helper.MAX_DOCUMENT_BYTES + 1), "1"), "invalid")
        # Worst-case sixfold JSON escaping of a valid 48 KiB document fits the frame.
        self.assert_ok(self.put("\x01" * helper.MAX_DOCUMENT_BYTES, "1"))
        self.seed([("/profile.md", "x" * (helper.MAX_DOCUMENT_BYTES + 100), 8)])
        self.assertEqual(len(self.call("list")["documents"][0]["content"]), helper.MAX_DOCUMENT_BYTES + 100)
        self.assert_ok(self.put("x" * (helper.MAX_DOCUMENT_BYTES + 100), "8"))
        shrunk = self.assert_ok(self.put("y" * (helper.MAX_DOCUMENT_BYTES + 50), "8"))["document"]
        self.assertEqual(shrunk["token"], "9")
        self.assert_error(self.put("z" * (helper.MAX_DOCUMENT_BYTES + 51), "9"), "invalid")
        self.assert_ok(self.put("small", "9"))

    def test_aggregate_count_and_bytes_budgets_are_not_partial(self):
        self.seed([("/doc-%04d.md" % n, "x", 1) for n in range(helper.MAX_DOCUMENTS)])
        self.assertEqual(len(self.call("list")["documents"]), helper.MAX_DOCUMENTS)
        self.assert_error(self.put("extra"), "invalid")
        self.assert_ok(self.put("edit existing", "1", "/doc-0000.md"))
        # Separate fresh fixture: one legacy document consumes the complete byte budget.
        self.root = self.base / "byte-budget"
        self.space_id = str(uuid.uuid4())
        self.assert_ok(self.call("create"))
        self.seed([("/large.md", "a" * helper.MAX_TOTAL_BYTES, 7)])
        self.assert_error(self.put("extra"), "invalid")
        self.assertEqual(self.call("status")["bytes"], helper.MAX_TOTAL_BYTES)
        self.seed([("/large.md", "a" * (helper.MAX_TOTAL_BYTES + 1), 7)])
        result = self.assert_error(self.call("list"), "invalid")
        self.assertNotIn("documents", result)

    def test_response_budget_rejects_whole_result_not_truncation(self):
        # Control characters expand sixfold in JSON; a valid active-byte budget can exceed frame budget.
        self.seed([("/legacy.md", "\x01" * (helper.MAX_RESPONSE_BYTES // 6 + 1), 4)])
        process, data = self.start(self.request("list"))
        stdout, stderr = process.communicate(data, timeout=60)
        self.assertEqual(stderr, b"")
        result = self.assert_error(json.loads(stdout), "invalid")
        self.assertLess(len(stdout), 1024)
        self.assertNotIn("documents", result)
        # Shrinking that same legacy baseline must work despite escaped journal baseline size.
        self.assert_ok(self.put("shrunk", "4", "/legacy.md"))

    def test_strict_protocol_frames_and_encoding(self):
        valid = frame(self.request("list"))
        boundary = valid[:-1] + b" " * (helper.MAX_REQUEST_BYTES - len(valid)) + b"\n"
        process, _ = self.start(self.request("list"))
        self.assert_ok(self.finish(process, boundary))
        process, _ = self.start(self.request("list"))
        self.assert_error(self.finish(process, boundary[:-1] + b" \n"), "invalid")
        frames = [valid + valid, valid.rstrip(b"\n"), b"\xff\n", b"[]\n", b"null\n",
                  b'{"protocol":1,"protocol":1,"op":"list","spaceId":"' + self.space_id.encode() + b'"}\n',
                  b" " * helper.MAX_REQUEST_BYTES + b"\n", b'{"number":NaN}\n']
        for data in frames:
            with self.subTest(frame=data[:60]):
                process, _ = self.start(self.request("list"))
                self.assert_error(self.finish(process, data), "invalid")
        for fields, code in (({"protocol": 2}, "unsupported"), ({"op": "exec"}, "unsupported"),
                             ({"spaceId": "../escape"}, "invalid"), ({"spaceId": "ABCDEF01-2345-4678-9234-56789abcdef0"}, "invalid"),
                             ({"protocol": True}, "invalid"), ({"extra": "not accepted"}, "invalid")):
            request = self.request("list")
            request.update(fields)
            self.assert_error(self.finish(*self.start(request)), code)
        for expected in (0, -1, "01", "-1", None):
            self.assert_error(self.call("read", path="/profile.md", expectedToken=expected), "invalid")
        self.assert_error(self.call("put", path="/profile.md", expectedToken="0", operationId="invalid", content="secret"), "invalid")
        self.assertFalse((self.base / "escape").exists())

    def run_cli(self, command, *options, failpoint=None):
        environment = dict(os.environ)
        environment.pop("MEMORY_SYNC_TEST_FAILPOINT", None)
        if failpoint:
            environment["MEMORY_SYNC_TEST_FAILPOINT"] = failpoint
        return subprocess.run([sys.executable, str(HELPER), command, "--root", str(self.root),
                               "--space", self.space_id, *options], capture_output=True, env=environment, timeout=30)

    def test_cli_draft_keeps_original_token_conflict_and_retries_crash(self):
        self.assert_ok(self.put("initial private body"))
        draft = self.base / "scratch.md"
        checkout = self.run_cli("checkout", "--path", "/profile.md", "--draft", str(draft))
        self.assertEqual(checkout.returncode, 0, checkout.stderr)
        self.assertNotIn(b"private body", checkout.stdout + checkout.stderr)
        self.assertEqual(draft.read_bytes(), b"initial private body")
        self.assertEqual(self.run_cli("checkout", "--path", "/profile.md", "--draft", str(draft)).returncode, 1)
        draft.write_bytes(b"my old draft")
        self.assert_ok(self.put("concurrent private body", "1"))
        conflict = self.run_cli("commit", "--draft", str(draft))
        self.assertEqual(conflict.returncode, 1)
        self.assert_error(json.loads(conflict.stdout), "conflict")
        self.assertNotIn(b"private body", conflict.stdout + conflict.stderr)
        baseline = json.loads(Path(str(draft) + ".memory-sync.json").read_bytes())
        self.assertEqual(baseline["token"], "1")
        self.assertEqual(draft.read_bytes(), b"my old draft")
        self.assertEqual(self.call("read", path="/profile.md")["document"]["content"], "concurrent private body")
        # Independent new checkout exercises crash-after-commit and UUID recovery.
        fresh = self.base / "fresh.md"
        self.assertEqual(self.run_cli("checkout", "--path", "/profile.md", "--draft", str(fresh)).returncode, 0)
        fresh.write_bytes(b"committed exactly once")
        crashed = self.run_cli("commit", "--draft", str(fresh), failpoint="receipt_durable")
        self.assertEqual(crashed.returncode, 86)
        pending = json.loads(Path(str(fresh) + ".memory-sync.json").read_bytes())["pending"]
        committed = self.run_cli("commit", "--draft", str(fresh))
        self.assertEqual(committed.returncode, 0, committed.stdout)
        self.assertEqual(json.loads(committed.stdout)["token"], "3")
        self.assertEqual(self.call("operationStatus", operationId=pending["operationId"])["document"]["token"], "3")
        baseline = json.loads(Path(str(fresh) + ".memory-sync.json").read_bytes())
        self.assertIsNone(baseline["pending"])
        self.assertEqual(baseline["token"], "3")
        deleted = self.run_cli("delete", "--path", "/profile.md", "--token", "3", "--operation-id", str(uuid.uuid4()))
        self.assertEqual(deleted.returncode, 0, deleted.stdout)
        status = self.run_cli("status")
        self.assertEqual(json.loads(status.stdout)["tombstones"], 1)

    def test_cli_disallows_drafts_inside_managed_root_and_symlinks(self):
        inside = self.root / "draft.md"
        result = self.run_cli("checkout", "--path", "/profile.md", "--draft", str(inside))
        self.assertEqual(result.returncode, 1)
        self.assertFalse(inside.exists())
        outside = self.base / "outside.md"
        outside.write_bytes(b"untouched")
        draft = self.base / "linked.md"
        draft.symlink_to(outside)
        self.assertEqual(self.run_cli("checkout", "--path", "/profile.md", "--draft", str(draft)).returncode, 1)
        self.assertEqual(outside.read_bytes(), b"untouched")

    def test_missing_space_and_required_state_are_not_reported_as_empty(self):
        current = self.space_id
        self.space_id = str(uuid.uuid4())
        self.assert_error(self.call("list"), "not_found")
        self.space_id = current
        index = self.space / "state" / "index.json"
        original = index.read_bytes()
        index.unlink()
        result = self.assert_error(self.call("list"), "not_found")
        self.assertNotIn("documents", result)
        index.write_bytes(b'{"format":1,"documents":{"broken":{}}}')
        self.assert_error(self.call("list"), "invalid")
        index.write_bytes(original)
        self.assert_ok(self.call("list"))

    def test_recovery_can_crash_again_and_still_keep_original_receipt(self):
        self.assert_ok(self.put("old"))
        request = self.request("put", path="/profile.md", expectedToken="1", content="new",
                               operationId=str(uuid.uuid4()))
        process, data = self.start(request, "journal_durable")
        process.communicate(data, timeout=30)
        self.assertEqual(process.returncode, 86)
        process, data = self.start(self.request("list"), "state_replaced")
        process.communicate(data, timeout=30)
        self.assertEqual(process.returncode, 86)
        recovered = self.assert_ok(self.call("operationStatus", operationId=request["operationId"]))
        self.assertTrue(recovered["found"])
        self.assertEqual(recovered["document"]["token"], "2")
        self.assertEqual(self.call("list")["documents"], [recovered["document"]])

    def test_cli_retry_of_edited_uncommitted_draft_keeps_original_baseline(self):
        self.assert_ok(self.put("original"))
        draft = self.base / "draft.md"
        self.assertEqual(self.run_cli("checkout", "--path", "/profile.md", "--draft", str(draft)).returncode, 0)
        draft.write_bytes(b"first attempt")
        crashed = self.run_cli("commit", "--draft", str(draft), failpoint="journal_temp_fsynced")
        self.assertEqual(crashed.returncode, 86)
        sidecar = Path(str(draft) + ".memory-sync.json")
        old_uuid = json.loads(sidecar.read_bytes())["pending"]["operationId"]
        draft.write_bytes(b"edited attempt")
        self.assert_ok(self.put("someone else", "1"))
        result = self.run_cli("commit", "--draft", str(draft))
        self.assertEqual(result.returncode, 1)
        self.assert_error(json.loads(result.stdout), "conflict")
        baseline = json.loads(sidecar.read_bytes())
        self.assertEqual(baseline["token"], "1")
        self.assertNotEqual(baseline["pending"]["operationId"], old_uuid)
        self.assertFalse(self.call("operationStatus", operationId=old_uuid)["found"])
        self.assertEqual(self.call("read", path="/profile.md")["document"]["content"], "someone else")

    def test_hostile_paths_are_json_data_not_shell_commands(self):
        path = "/$(touch injected); 'quote'.md"
        self.assert_ok(self.put("literal content", path=path))
        self.assertEqual(self.call("read", path=path)["document"]["content"], "literal content")
        self.assertFalse((self.base / "injected").exists())


if __name__ == "__main__":
    unittest.main()
