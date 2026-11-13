#!/usr/bin/env python3
"""Memory Sync RPC v1 and token-preserving local draft CLI (POSIX, Python 3.9+)."""
import argparse
import base64
import contextlib
import errno
import fcntl
import hashlib
import json
import os
import re
import stat
import sys
import uuid

PROTOCOL = 1
MAX_REQUEST_BYTES = 512 * 1024
MAX_RESPONSE_BYTES = 128 * 1024 * 1024
MAX_DOCUMENT_BYTES = 48 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_DOCUMENTS = 2000
FAILPOINTS = (
    "journal_temp_fsynced", "journal_replaced", "journal_durable", "document_temp_fsynced",
    "document_replaced", "document_durable", "state_temp_fsynced",
    "state_replaced", "state_durable", "receipt_temp_fsynced",
    "receipt_replaced", "receipt_durable", "journal_removed", "transaction_durable",
)
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
FILE_FLAGS = os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK


class SyncError(Exception):
    def __init__(self, code, message, document=None):
        super().__init__(message)
        self.code = code
        self.document = document


def invalid(message="Invalid request"):
    raise SyncError("invalid", message)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            invalid("Duplicate JSON field")
        result[key] = value
    return result


def decode(data):
    try:
        return json.loads(data.decode("utf-8", errors="strict"),
                          object_pairs_hook=unique_object,
                          parse_constant=lambda _: invalid("Invalid JSON number"))
    except (ValueError, UnicodeError, RecursionError):
        invalid("Invalid UTF-8 JSON")


def canonical_uuid(value):
    if not isinstance(value, str):
        invalid("Expected canonical UUID")
    try:
        if str(uuid.UUID(value)) != value:
            invalid("Expected canonical UUID")
    except ValueError:
        invalid("Expected canonical UUID")
    return value


def memory_path(value):
    if not isinstance(value, str):
        invalid("Expected absolute Markdown path")
    try:
        length = len(value.encode("utf-16-le")) // 2
        value.encode("utf-8")
    except UnicodeError:
        invalid("Invalid path encoding")
    if (not value.startswith("/") or length > 128 or not value.endswith(".md")
            or "\\" in value or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value)):
        invalid("Invalid absolute Markdown path")
    parts = value[1:].split("/")
    if any(not p.strip() or p.strip() != p or p.startswith(".") for p in parts):
        invalid("Invalid Markdown path segment")
    return value


def token(value):
    if not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]*", value):
        invalid("Expected generation token string")
    return value


def validate_request(request):
    if not isinstance(request, dict) or type(request.get("protocol")) is not int:
        invalid("Expected RPC object with protocol")
    if request["protocol"] != PROTOCOL:
        raise SyncError("unsupported", "Unsupported protocol")
    op = request.get("op")
    fields = {
        "create": set(), "list": set(), "status": set(),
        "read": {"path", "expectedToken"},
        "put": {"path", "expectedToken", "operationId", "content"},
        "delete": {"path", "expectedToken", "operationId"},
        "operationStatus": {"operationId"},
    }
    if not isinstance(op, str) or op not in fields:
        raise SyncError("unsupported", "Unsupported operation")
    if set(request) - ({"protocol", "op", "spaceId"} | fields[op]):
        invalid("Unknown request field")
    canonical_uuid(request.get("spaceId"))
    if op in ("read", "put", "delete"):
        memory_path(request.get("path"))
    if op in ("put", "delete") or "expectedToken" in request:
        token(request.get("expectedToken"))
    if op in ("put", "delete", "operationStatus"):
        canonical_uuid(request.get("operationId"))
    if op == "put":
        if not isinstance(request.get("content"), str):
            invalid("Expected Markdown string")
        try:
            request["content"].encode("utf-8", errors="strict")
        except UnicodeError:
            invalid("Invalid Markdown encoding")
    return request


def failpoint(name):
    # Deliberately abrupt, test-only crash; no production retry or fake result.
    if os.environ.get("MEMORY_SYNC_TEST_FAILPOINT") == name:
        os._exit(86)


@contextlib.contextmanager
def directory(parent, name, create=False):
    if create:
        try:
            os.mkdir(name, 0o700, dir_fd=parent)
            os.fsync(parent)
        except FileExistsError:
            pass
    fd = os.open(name, DIR_FLAGS, dir_fd=parent)
    try:
        yield fd
    finally:
        os.close(fd)


@contextlib.contextmanager
def absolute_directory(path, create=False):
    if not isinstance(path, str) or not path.startswith("/"):
        invalid("Root must be absolute")
    parts = path.split("/")[1:]
    if not parts or any(p in ("", ".", "..") for p in parts):
        invalid("Root must be a canonical absolute directory, not /")
    with contextlib.ExitStack() as stack:
        fd = stack.enter_context(directory(None, "/"))
        for part in parts:
            fd = stack.enter_context(directory(fd, part, create))
        yield fd


def read_bytes(parent, name, limit=None):
    fd = os.open(name, FILE_FLAGS, dir_fd=parent)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise SyncError("drift", "Non-regular or hard-linked managed file")
        if limit is not None and info.st_size > limit:
            invalid("Stored data exceeds budget")
        chunks = []
        size = 0
        while True:
            chunk = os.read(fd, 64 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if limit is not None and size > limit:
                invalid("Stored data exceeds budget")
            chunks.append(chunk)
        return b"".join(chunks)
    finally:
        os.close(fd)


def optional_bytes(parent, name, limit=None):
    try:
        return read_bytes(parent, name, limit)
    except FileNotFoundError:
        return None


def atomic_write(parent, name, data, stage=None, temporary=None):
    temporary = temporary or ".tmp-" + str(uuid.uuid4())
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
                 | os.O_CLOEXEC, 0o600, dir_fd=parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if stage:
            failpoint(stage + "_temp_fsynced")
        os.replace(temporary, name, src_dir_fd=parent, dst_dir_fd=parent)
        if stage:
            failpoint(stage + "_replaced")
        os.fsync(parent)
        if stage:
            failpoint(stage + "_durable")
    finally:
        try:
            os.unlink(temporary, dir_fd=parent)
        except FileNotFoundError:
            pass


def document(path, entry, content=None):
    return {"path": path, "token": entry["token"] if entry else "0", "content": content}


def metadata(doc):
    content = doc["content"]
    data = content.encode("utf-8") if content is not None else None
    return {"token": doc["token"], "sha256": hashlib.sha256(data).hexdigest()
            if data is not None else None, "bytes": len(data) if data is not None else 0}


def check_paths(paths):
    names = {}
    files = set(paths)
    for path in paths:
        memory_path(path)
        prefix = ""
        for segment in path[1:].split("/"):
            prefix += "/" + segment
            previous = names.setdefault(prefix.casefold(), prefix)
            if previous != prefix:
                invalid("Casefold path collision")
            if prefix != path and prefix in files:
                invalid("File/directory path collision")


class Space:
    def __init__(self, fd):
        self.fd = fd
        self.stack = contextlib.ExitStack()
        self.memories = self.stack.enter_context(directory(fd, "memories"))
        self.state = self.stack.enter_context(directory(fd, "state"))
        self.receipts = self.stack.enter_context(directory(self.state, "receipts"))
        self.recovery = self.stack.enter_context(directory(fd, "recovery"))
        self.drifts = self.stack.enter_context(directory(self.recovery, "drift"))
        self.index = None

    def close(self):
        self.stack.close()

    def load_index(self):
        value = decode(read_bytes(self.state, "index.json", MAX_RESPONSE_BYTES))
        if (not isinstance(value, dict) or value.get("format") != 1
                or not isinstance(value.get("documents"), dict)):
            invalid("Invalid space state")
        self.index = value["documents"]
        check_paths(self.index)
        count = total = 0
        for entry in self.index.values():
            if not isinstance(entry, dict) or set(entry) != {"token", "sha256", "bytes"}:
                invalid("Invalid document state")
            token(entry["token"])
            if entry["token"] == "0" or type(entry["bytes"]) is not int or entry["bytes"] < 0:
                invalid("Invalid document state")
            if entry["sha256"] is not None:
                if not isinstance(entry["sha256"], str) or not re.fullmatch("[0-9a-f]{64}", entry["sha256"]):
                    invalid("Invalid document digest")
                count += 1
                total += entry["bytes"]
            elif entry["bytes"] != 0:
                invalid("Invalid tombstone")
        if count > MAX_DOCUMENTS or total > MAX_TOTAL_BYTES:
            invalid("Space exceeds document or byte budget")

    @contextlib.contextmanager
    def parent(self, path, create=False):
        parts = path[1:].split("/")
        with contextlib.ExitStack() as stack:
            fd = self.memories
            for part in parts[:-1]:
                fd = stack.enter_context(directory(fd, part, create))
            yield fd, parts[-1]

    def raw(self, path):
        try:
            with self.parent(path) as (fd, name):
                return optional_bytes(fd, name, MAX_TOTAL_BYTES)
        except FileNotFoundError:
            return None

    def preserve_drift(self, path, data=None, kind="modified"):
        # Evidence is private and never echoed to diagnostics. Original managed file is untouched.
        evidence = {"format": 1, "path": path, "kind": kind,
                    "expected": self.index.get(path) if self.index else None,
                    "observedBase64": base64.b64encode(data).decode("ascii")
                    if data is not None else None}
        identity = hashlib.sha256(encoded(evidence)).hexdigest() + ".json"
        atomic_write(self.drifts, identity, encoded(evidence))
        raise SyncError("drift", "Managed Markdown drift; evidence preserved in recovery/drift")

    def checked_raw(self, path):
        try:
            return self.raw(path)
        except SyncError as exc:
            if exc.code == "drift":
                self.preserve_drift(path, kind="unsafe_file")
            raise
        except OSError as exc:
            if exc.errno in (errno.ELOOP, errno.ENOTDIR):
                self.preserve_drift(path, kind="unsafe_path")
            raise

    def actual_document(self, path):
        entry = self.index.get(path)
        data = self.checked_raw(path)
        expected = entry["sha256"] if entry else None
        if data is None:
            if expected is not None:
                self.preserve_drift(path, kind="missing")
            return document(path, entry)
        if expected is None or hashlib.sha256(data).hexdigest() != expected:
            self.preserve_drift(path, data)
        try:
            content = data.decode("utf-8", errors="strict")
        except UnicodeError:
            self.preserve_drift(path, data, "invalid_utf8")
        if len(data) != entry["bytes"]:
            self.preserve_drift(path, data)
        return document(path, entry, content)

    def scan(self):
        # Scan *all* files, not just known paths: unknown raw additions never become gen0 writes.
        known_directories = set()
        for path in self.index:
            parts = path[1:].split("/")
            for end in range(1, len(parts)):
                known_directories.add("/" + "/".join(parts[:end]))
        def walk(fd, prefix):
            for name in sorted(os.listdir(fd)):
                info = os.stat(name, dir_fd=fd, follow_symlinks=False)
                path = prefix + "/" + name
                if stat.S_ISLNK(info.st_mode) or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
                    self.preserve_drift(path, kind="unsafe_path")
                if stat.S_ISREG(info.st_mode) and info.st_nlink != 1:
                    self.preserve_drift(path, kind="unsafe_file")
                if stat.S_ISREG(info.st_mode) and info.st_size > MAX_TOTAL_BYTES:
                    self.preserve_drift(path, kind="oversized_raw_file_left_in_place")
                if stat.S_ISDIR(info.st_mode):
                    if path not in known_directories:
                        self.preserve_drift(path, kind="untracked_directory")
                    # Retained empty directories can belong to tombstoned paths.
                    if (name.startswith(".") or name.strip() != name or not name.strip()
                            or "\\" in name or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name)):
                        self.preserve_drift(path, kind="invalid_directory")
                    with directory(fd, name) as child:
                        walk(child, path)
                else:
                    try:
                        memory_path(path)
                    except SyncError:
                        self.preserve_drift(path, read_bytes(fd, name, MAX_TOTAL_BYTES), "invalid_path")
                    if path not in self.index or self.index[path]["sha256"] is None:
                        self.preserve_drift(path, read_bytes(fd, name, MAX_TOTAL_BYTES), "untracked")
        walk(self.memories, "")
        documents = [self.actual_document(path) for path in sorted(self.index)]
        return documents

    def write_document(self, doc, operation_id):
        content = doc["content"]
        if content is None:
            try:
                with self.parent(doc["path"]) as (fd, name):
                    try:
                        os.unlink(name, dir_fd=fd)
                    except FileNotFoundError:
                        pass
                    failpoint("document_replaced")
                    os.fsync(fd)
            except FileNotFoundError:
                pass
            failpoint("document_durable")
        else:
            with self.parent(doc["path"], create=True) as (fd, name):
                temporary = ".tmp-" + operation_id
                staged = optional_bytes(fd, temporary, MAX_TOTAL_BYTES)
                if staged is not None:
                    if staged != content.encode("utf-8"):
                        self.preserve_drift(doc["path"], staged, "staging_content_changed")
                    os.unlink(temporary, dir_fd=fd)
                    os.fsync(fd)
                atomic_write(fd, name, content.encode("utf-8"), "document", temporary)

    def receipt(self, operation_id):
        data = optional_bytes(self.receipts, operation_id + ".json", MAX_RESPONSE_BYTES)
        if data is None:
            return None
        value = decode(data)
        if (not isinstance(value, dict) or set(value) != {"digest", "document"}
                or not isinstance(value["digest"], str)
                or not re.fullmatch("[0-9a-f]{64}", value["digest"])):
            invalid("Invalid operation receipt")
        self.validate_document(value["document"])
        return value

    @staticmethod
    def validate_document(doc):
        if not isinstance(doc, dict) or set(doc) != {"path", "token", "content"}:
            invalid("Invalid persisted document")
        memory_path(doc["path"])
        token(doc["token"])
        if doc["content"] is not None and not isinstance(doc["content"], str):
            invalid("Invalid persisted content")
        if doc["content"] is not None:
            try:
                doc["content"].encode("utf-8")
            except UnicodeError:
                invalid("Invalid persisted content encoding")

    def finish(self, journal):
        new = journal["new"]
        self.write_document(new, journal["operationId"])
        updated = dict(self.index)
        updated[new["path"]] = metadata(new)
        atomic_write(self.state, "index.json", encoded({"format": 1, "documents": updated}), "state")
        self.index = updated
        atomic_write(self.receipts, journal["operationId"] + ".json",
                     encoded(journal["receipt"]), "receipt")
        os.unlink("journal.json", dir_fd=self.recovery)
        failpoint("journal_removed")
        os.fsync(self.recovery)
        failpoint("transaction_durable")

    def recover(self):
        self.load_index()
        data = optional_bytes(self.recovery, "journal.json", MAX_RESPONSE_BYTES)
        if data is None:
            return
        journal = decode(data)
        if isinstance(journal, dict) and isinstance(journal.get("old"), dict):
            old_encoded = journal["old"]
            if "contentBase64" not in old_encoded or "content" in old_encoded:
                invalid("Invalid encoded journal baseline")
            raw = old_encoded.pop("contentBase64")
            try:
                old_encoded["content"] = (base64.b64decode(raw, validate=True).decode("utf-8")
                                          if raw is not None else None)
            except (ValueError, UnicodeError, TypeError):
                invalid("Invalid encoded journal baseline")
        if (not isinstance(journal, dict) or set(journal) != {"format", "operationId", "old", "new", "receipt"}
                or journal["format"] != 1):
            invalid("Invalid recovery journal")
        canonical_uuid(journal["operationId"])
        old, new = journal["old"], journal["new"]
        self.validate_document(old)
        self.validate_document(new)
        if (not isinstance(journal["receipt"], dict)
                or set(journal["receipt"]) != {"digest", "document"}
                or old["path"] != new["path"] or journal["receipt"].get("document") != new):
            invalid("Inconsistent recovery journal")
        if (not isinstance(journal["receipt"].get("digest"), str)
                or not re.fullmatch("[0-9a-f]{64}", journal["receipt"]["digest"])):
            invalid("Invalid journal digest")
        path = new["path"]
        current = self.index.get(path)
        allowed_entries = (metadata(old) if old["token"] != "0" else None, metadata(new))
        if current not in allowed_entries:
            self.preserve_drift(path, kind="recovery_state_changed")
        raw = self.checked_raw(path)
        allowed_content = tuple(d["content"].encode("utf-8") if d["content"] is not None else None
                                for d in (old, new))
        if raw not in allowed_content:
            self.preserve_drift(path, raw, "recovery_content_changed")
        existing = self.receipt(journal["operationId"])
        if existing is not None and existing != journal["receipt"]:
            invalid("Inconsistent recovery receipt")
        self.finish(journal)

    def mutate(self, request, current):
        op_id = request["operationId"]
        digest = hashlib.sha256(encoded(request)).hexdigest()
        receipt = self.receipt(op_id)
        if receipt is not None:
            if receipt["digest"] != digest:
                invalid("Operation UUID already belongs to a different request")
            return {"document": receipt["document"]}
        if current["token"] != request["expectedToken"]:
            raise SyncError("conflict", "Generation changed", current)
        path = request["path"]
        new_content = request.get("content") if request["op"] == "put" else None
        old_size = len(current["content"].encode("utf-8")) if current["content"] is not None else 0
        new_size = len(new_content.encode("utf-8")) if new_content is not None else 0
        if new_size > MAX_DOCUMENT_BYTES and new_size > old_size:
            invalid("New or growing document exceeds 48 KiB")
        count = sum(e["sha256"] is not None for e in self.index.values())
        total = sum(e["bytes"] for e in self.index.values())
        count += int(new_content is not None) - int(current["content"] is not None)
        if count > MAX_DOCUMENTS or total - old_size + new_size > MAX_TOTAL_BYTES:
            invalid("Space exceeds document or byte budget")
        # Equal content is still receipted but not a new generation; stale CAS always conflicts.
        generation = current["token"] if current["content"] == new_content else str(int(current["token"]) + 1)
        new = {"path": path, "token": generation, "content": new_content}
        result = {"digest": digest, "document": new}
        journal = {"format": 1, "operationId": op_id, "old": current, "new": new, "receipt": result}
        # Base64 avoids sixfold JSON escaping of a large legacy baseline.
        persisted = dict(journal)
        persisted["old"] = {"path": current["path"], "token": current["token"],
                            "contentBase64": base64.b64encode(current["content"].encode("utf-8")).decode("ascii")
                            if current["content"] is not None else None}
        # A delete of never-existing path creates a retained absence generation.
        if generation == "0":
            new["token"] = "1"
        prospective = dict(self.index)
        prospective[path] = metadata(new)
        if len(encoded({"format": 1, "documents": prospective})) > MAX_RESPONSE_BYTES:
            invalid("Retained state exceeds storage budget")
        journal_data = encoded(persisted)
        if len(journal_data) > MAX_RESPONSE_BYTES:
            invalid("Recovery journal exceeds budget")
        atomic_write(self.recovery, "journal.json", journal_data, "journal")
        self.finish(journal)
        return {"document": new}

    def execute(self, request):
        self.recover()
        documents = self.scan()
        op = request["op"]
        if op == "list":
            return {"documents": documents}
        if op == "status":
            return {"spaceId": request["spaceId"],
                    "documents": sum(d["content"] is not None for d in documents),
                    "tombstones": sum(d["content"] is None for d in documents),
                    "bytes": sum(e["bytes"] for e in self.index.values()),
                    "receipts": sum(n.endswith(".json") for n in os.listdir(self.receipts)),
                    "driftRecords": sum(n.endswith(".json") for n in os.listdir(self.drifts))}
        if op == "operationStatus":
            receipt = self.receipt(request["operationId"])
            return {"found": False} if receipt is None else {"found": True, "document": receipt["document"]}
        check_paths(set(self.index) | {request["path"]})
        current = self.actual_document(request["path"])
        if op == "read":
            if "expectedToken" in request and request["expectedToken"] != current["token"]:
                raise SyncError("conflict", "Generation changed", current)
            return {"document": current}
        return self.mutate(request, current)


def create_space(spaces, space_id):
    try:
        os.stat(space_id, dir_fd=spaces, follow_symlinks=False)
    except FileNotFoundError:
        pass
    else:
        raise SyncError("already_exists", "Space already exists")
    # The staged complete empty space is published by one durable directory rename.
    staging = ".creating-" + space_id
    try:
        os.mkdir(staging, 0o700, dir_fd=spaces)
    except FileExistsError:
        # An interrupted unpublished create may be reused only when its complete structure validates.
        pass
    with directory(spaces, staging) as fd:
        with directory(fd, "memories", True):
            pass
        with directory(fd, "state", True) as state_fd:
            with directory(state_fd, "receipts", True):
                pass
            if optional_bytes(state_fd, "index.json", MAX_RESPONSE_BYTES) is None:
                atomic_write(state_fd, "index.json", encoded({"format": 1, "documents": {}}))
        with directory(fd, "recovery", True) as recovery_fd:
            with directory(recovery_fd, "drift", True):
                pass
        space = Space(fd)
        try:
            space.recover()
            if space.scan() or os.listdir(space.receipts) or os.listdir(space.drifts):
                invalid("Interrupted create contains unexpected data")
        finally:
            space.close()
        os.fsync(fd)
    os.rename(staging, space_id, src_dir_fd=spaces, dst_dir_fd=spaces)
    os.fsync(spaces)
    return {"spaceId": space_id}


def dispatch(root, request):
    validate_request(request)
    create = request["op"] == "create"
    with absolute_directory(root, create=create) as root_fd:
        with directory(root_fd, "spaces", create=create) as spaces:
            with directory(root_fd, "locks", create=create) as locks:
                lock_fd = os.open(request["spaceId"] + ".lock", os.O_RDWR | os.O_CREAT
                                  | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK,
                                  0o600, dir_fd=locks)
                try:
                    info = os.fstat(lock_fd)
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                        raise SyncError("permission", "Unsafe space lock")
                    fcntl.flock(lock_fd, fcntl.LOCK_EX)
                    if create:
                        return create_space(spaces, request["spaceId"])
                    with directory(spaces, request["spaceId"]) as space_fd:
                        space = Space(space_fd)
                        try:
                            return space.execute(request)
                        finally:
                            space.close()
                finally:
                    os.close(lock_fd)


def response_for(root, request):
    try:
        result = {"protocol": PROTOCOL, "ok": True, **dispatch(root, request)}
        if len(encoded(result)) + 1 > MAX_RESPONSE_BYTES:
            invalid("Response exceeds 128 MiB; no partial results returned")
        return result
    except SyncError as exc:
        result = {"protocol": PROTOCOL, "ok": False,
                  "error": {"code": exc.code, "message": str(exc)}}
        if exc.document is not None:
            result["document"] = exc.document
        if len(encoded(result)) + 1 > MAX_RESPONSE_BYTES:
            return error_response("invalid", "Response exceeds 128 MiB; no partial results returned")
        return result
    except FileNotFoundError:
        return error_response("not_found", "Space or required managed data not found")
    except OSError as exc:
        if exc.errno in (errno.EACCES, errno.EPERM, errno.ELOOP, errno.ENOTDIR):
            return error_response("permission", "Unsafe or inaccessible managed path")
        return error_response("invalid", "Managed storage IO failure")
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
        return error_response("invalid", "Invalid managed state")


def error_response(code, message):
    return {"protocol": PROTOCOL, "ok": False, "error": {"code": code, "message": message}}


def rpc(root):
    try:
        frame = sys.stdin.buffer.readline(MAX_REQUEST_BYTES + 1)
        if len(frame) > MAX_REQUEST_BYTES or not frame.endswith(b"\n"):
            invalid("Request must be one newline-delimited frame of at most 512 KiB")
        if sys.stdin.buffer.read(1):
            invalid("Expected exactly one request frame")
        request = decode(frame[:-1])
        result = response_for(root, request)
    except SyncError as exc:
        result = error_response(exc.code, str(exc))
    except (ValueError, TypeError, RecursionError):
        result = error_response("invalid", "Invalid request")
    sys.stdout.buffer.write(encoded(result) + b"\n")
    sys.stdout.buffer.flush()
    # A valid envelope is a successful transport, including protocol/CAS errors.
    return 0


def local_read(path, limit):
    absolute = os.path.abspath(path)
    with absolute_directory(os.path.dirname(absolute)) as fd:
        return read_bytes(fd, os.path.basename(absolute), limit)


def local_write(path, data, exclusive=False):
    absolute = os.path.abspath(path)
    with absolute_directory(os.path.dirname(absolute)) as fd:
        name = os.path.basename(absolute)
        if exclusive:
            handle = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
                             | os.O_CLOEXEC, 0o600, dir_fd=fd)
            with os.fdopen(handle, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.fsync(fd)
        else:
            info = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                invalid("Unsafe draft sidecar")
            atomic_write(fd, name, data)


@contextlib.contextmanager
def draft_lock(path):
    absolute = os.path.abspath(path)
    with absolute_directory(os.path.dirname(absolute)) as parent:
        name = os.path.basename(absolute) + ".memory-sync.lock"
        fd = os.open(name, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC
                     | os.O_NONBLOCK, 0o600, dir_fd=parent)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                invalid("Unsafe draft lock")
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)


def cli(args):
    request = {"protocol": PROTOCOL, "op": args.command, "spaceId": args.space}
    if args.command == "checkout":
        request.update(op="read", path=args.path)
        result = response_for(args.root, request)
        if not result["ok"]:
            return result
        doc = result["document"]
        # Do not publish a usable baseline until both draft and baseline are durable.
        baseline = {"format": 1, "root": args.root, "spaceId": args.space,
                    "path": args.path, "token": doc["token"], "pending": None}
        local_write(args.draft, (doc["content"] or "").encode("utf-8"), exclusive=True)
        local_write(args.draft + ".memory-sync.json", encoded(baseline), exclusive=True)
        return {"protocol": PROTOCOL, "ok": True, "token": doc["token"],
                "exists": doc["content"] is not None}
    if args.command == "commit":
        sidecar_path = args.draft + ".memory-sync.json"
        baseline = decode(local_read(sidecar_path, MAX_REQUEST_BYTES))
        if (not isinstance(baseline, dict) or baseline.get("format") != 1
                or baseline.get("root") != args.root or baseline.get("spaceId") != args.space):
            invalid("Draft baseline does not match root and space")
        content_bytes = local_read(args.draft, MAX_REQUEST_BYTES)
        try:
            content = content_bytes.decode("utf-8", errors="strict")
        except UnicodeError:
            invalid("Draft is not UTF-8")
        path = memory_path(baseline.get("path"))
        expected = token(baseline.get("token"))
        pending = baseline.get("pending")
        digest = hashlib.sha256(content_bytes).hexdigest()
        if pending is not None:
            if not isinstance(pending, dict) or set(pending) != {"operationId", "sha256"}:
                invalid("Invalid pending draft operation")
            canonical_uuid(pending["operationId"])
            if not isinstance(pending["sha256"], str) or not re.fullmatch("[0-9a-f]{64}", pending["sha256"]):
                invalid("Invalid pending draft digest")
            # Query a previously persisted operation before permitting edits/new UUIDs.
            lookup = response_for(args.root, {"protocol": PROTOCOL, "op": "operationStatus",
                                             "spaceId": args.space, "operationId": pending["operationId"]})
            if not lookup["ok"]:
                return lookup
            if lookup["found"]:
                committed = lookup["document"]
                if (committed["path"] != path or committed["content"] is None
                        or hashlib.sha256(committed["content"].encode("utf-8")).hexdigest() != pending["sha256"]):
                    invalid("Pending UUID result does not match submitted draft")
                baseline["token"] = lookup["document"]["token"]
                baseline["pending"] = None
                local_write(sidecar_path, encoded(baseline))
                if pending["sha256"] == digest:
                    return {"protocol": PROTOCOL, "ok": True, "token": baseline["token"]}
                expected = baseline["token"]
                pending = None
            elif pending["sha256"] != digest:
                # Recovery confirmed no commit. A genuinely edited draft is a new operation,
                # still based on the *original* checkout token, never a newly fetched token.
                pending = None
        if pending is None:
            pending = {"operationId": str(uuid.uuid4()), "sha256": digest}
            baseline["pending"] = pending
            local_write(sidecar_path, encoded(baseline))
        request.update(op="put", path=path, expectedToken=expected,
                       operationId=pending["operationId"], content=content)
        if len(encoded(request)) + 1 > MAX_REQUEST_BYTES:
            invalid("Draft request exceeds 512 KiB")
        result = response_for(args.root, request)
        if result["ok"]:
            baseline["token"] = result["document"]["token"]
            baseline["pending"] = None
            local_write(sidecar_path, encoded(baseline))
            return {"protocol": PROTOCOL, "ok": True, "token": baseline["token"]}
        # A failed CAS is not a committed UUID; keep the original baseline and draft.
        return result
    if args.command == "delete":
        request.update(path=args.path, expectedToken=args.token, operationId=args.operation_id)
    return response_for(args.root, request)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("rpc", "create", "checkout", "commit", "delete", "status"):
        sub = commands.add_parser(name)
        sub.add_argument("--root", required=True, help="Private absolute managed data root")
        if name != "rpc":
            sub.add_argument("--space", required=True, help="Canonical space UUID")
        if name in ("checkout", "delete"):
            sub.add_argument("--path", required=True, help="Absolute virtual Markdown path")
        if name in ("checkout", "commit"):
            sub.add_argument("--draft", required=True, help="Scratch Markdown outside managed root")
        if name == "delete":
            sub.add_argument("--token", required=True, help="Original baseline generation, never latest")
            sub.add_argument("--operation-id", required=True, help="Stable UUID retained across retries")
    args = parser.parse_args(argv)
    if args.command == "rpc":
        return rpc(args.root)
    try:
        if args.command in ("checkout", "commit"):
            root = os.path.abspath(args.root)
            draft = os.path.abspath(args.draft)
            if os.path.commonpath((root, draft)) == root:
                invalid("Draft must be outside managed root")
            with draft_lock(args.draft):
                result = cli(args)
        else:
            result = cli(args)
    except SyncError as exc:
        result = error_response(exc.code, str(exc))
    except OSError:
        result = error_response("permission", "Draft IO failed; existing files are not overwritten on checkout")
    except (ValueError, TypeError, KeyError, RecursionError):
        result = error_response("invalid", "Invalid draft baseline")
    # CLI never prints Markdown, including conflict response bodies.
    result.pop("document", None)
    sys.stdout.buffer.write(encoded(result) + b"\n")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
