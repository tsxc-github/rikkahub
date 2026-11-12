#!/usr/bin/env python3
"""P0 device-side OpenSSH probe. No LAN listener, account edits or existing keys.

Run inside RikkaHub's real PRoot. This is NOT proof of the Kotlin background
handle's cleanup, external-computer reachability or an integrated Android FGS.
All private test material is removed in finally; only controlled logs survive.
"""
import json
import os
from pathlib import Path
import platform
import selectors
import shutil
import socket
import subprocess
import tempfile
import threading
import time

BASE = Path(__file__).resolve().parent
LOGS = BASE / 'logs'
LOGS.mkdir(exist_ok=True)
REPORT = {'scope': 'real phone PRoot; loopback; standalone probe',
          'target_commit': '068dea4917e534cb3ed186ae963df98e66c084a4',
          'platform': platform.uname()._asdict(), 'tests': {}}
CHILDREN = []


def run(args, stdin=None, timeout=12):
    p = subprocess.run(args, input=stdin, text=True, capture_output=True,
                       timeout=timeout)
    return {'exit': p.returncode, 'stdout': p.stdout, 'stderr': p.stderr}


def record(name, result):
    REPORT['tests'][name] = result
    print(name, json.dumps(result, ensure_ascii=False), flush=True)


def wait_listening(proc):
    lines = []
    deadline = time.monotonic() + 8
    with selectors.DefaultSelector() as selector:
        selector.register(proc.stderr, selectors.EVENT_READ)
        while time.monotonic() < deadline:
            if not selector.select(max(0, deadline - time.monotonic())):
                break
            line = proc.stderr.readline()
            if not line:
                break
            lines.append(line)
            if 'Server listening on' in line:
                return True, ''.join(lines)
    return False, ''.join(lines)


def probe(tmp):
    for binary in ('sshd', 'ssh', 'ssh-keygen', 'sftp', 'bash'):
        if shutil.which(binary) is None:
            raise RuntimeError('missing dependency: ' + binary)
    REPORT['openssh'] = run(['/usr/sbin/sshd', '-V'])
    REPORT['libc'] = run(['ldd', '--version'])
    for line in Path('/proc/self/status').read_text().splitlines():
        if line.startswith(('Uid:', 'CapEff:')):
            REPORT.setdefault('process', []).append(line)
    try:
        REPORT['selinux'] = Path('/proc/self/attr/current').read_text().strip('\x00\n')
    except OSError:
        pass
    for name in ('host_ed25519', 'client_ed25519'):
        result = run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(tmp / name)])
        if result['exit'] != 0:
            record('key_generation', result)
            return
    auth = tmp / 'authorized_keys'
    auth.write_text((tmp / 'client_ed25519.pub').read_text())
    auth.chmod(0o600)
    # Refuse to touch an already bound port; this probe never kills its owner.
    with socket.socket() as test:
        test.bind(('127.0.0.1', 2222))
    cfg = tmp / 'sshd_config'
    cfg.write_text(f'''Port 2222
ListenAddress 127.0.0.1
HostKey {tmp}/host_ed25519
PidFile {tmp}/sshd.pid
PermitRootLogin prohibit-password
PubkeyAuthentication yes
AuthenticationMethods publickey
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitEmptyPasswords no
UsePAM no
AuthorizedKeysFile {auth}
StrictModes yes
AllowTcpForwarding yes
AllowStreamLocalForwarding yes
AllowAgentForwarding no
GatewayPorts no
X11Forwarding no
PermitTunnel no
Subsystem sftp internal-sftp
LogLevel VERBOSE
''')
    cfg.chmod(0o600)
    REPORT['host_fingerprint'] = run(['ssh-keygen', '-lf', str(tmp / 'host_ed25519.pub')])['stdout'].strip()
    checked = run(['/usr/sbin/sshd', '-t', '-f', str(cfg)])
    record('configuration', checked)
    if checked['exit']:
        return
    known = tmp / 'known_hosts'
    host_type, host_blob, *_ = (tmp / 'host_ed25519.pub').read_text().split()
    known.write_text(f'[127.0.0.1]:2222 {host_type} {host_blob}\n')
    known.chmod(0o600)
    server = subprocess.Popen(['/usr/sbin/sshd', '-D', '-e', '-f', str(cfg)],
                              stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    CHILDREN.append(server)
    ready, log = wait_listening(server)
    record('listener', {'ready': ready, 'log': log})
    if not ready:
        return
    # Verification is against the exact key just generated locally, never TOFU
    # and never StrictHostKeyChecking=no. Do not use ambient SSH config/agent.
    ssh = ['ssh', '-F', '/dev/null', '-i', str(tmp / 'client_ed25519'),
           '-p', '2222', '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
           '-o', 'IdentityAgent=none', '-o', 'StrictHostKeyChecking=yes',
           '-o', f'UserKnownHostsFile={known}', '-o', 'GlobalKnownHostsFile=/dev/null',
           '-o', 'ConnectTimeout=5']
    command = run(ssh + ['root@127.0.0.1', 'cd /workspace && printf "P0_AUTH_OK\\n" && pwd && test -d /skills'])
    record('public_key_command', command)
    # Do not hide a failed login by weakening authentication/account settings.
    if command['exit']:
        return
    record('forced_pty', run(ssh + ['-tt', 'root@127.0.0.1',
                                  'test -t 0 && cd /workspace && printf "P0_PTY_OK\\n"']))
    repo = tmp / 'git-check'
    record('git_init', run(['git', 'init', '-q', str(repo)]))
    record('git_status', run(ssh + ['root@127.0.0.1', f'git -C {repo} status --porcelain']))
    local = tmp / 'upload.txt'
    local.write_text('reviewed-on-pc\n')
    remote = BASE / 'p0-transfer.tmp'
    renamed = BASE / 'p0-renamed.tmp'
    try:
        sftp = ['sftp'] + ssh[1:] + ['-b', '-', 'root@127.0.0.1']
        # sftp uses -P, not ssh's -p.
        sftp[sftp.index('-p')] = '-P'
        batch = f'put {local} {remote}\nrename {remote} {renamed}\nget {renamed} {tmp}/download.txt\nrm {renamed}\n'
        result = run(sftp, stdin=batch)
        result['content_matches'] = (tmp / 'download.txt').exists() and (tmp / 'download.txt').read_text() == local.read_text()
        record('sftp_roundtrip', result)
    finally:
        remote.unlink(missing_ok=True)
        renamed.unlink(missing_ok=True)
    with socket.socket() as echo:
        echo.bind(('127.0.0.1', 0))
        echo.listen(1)
        echo.settimeout(10)
        errors = []
        def serve():
            try:
                conn, _ = echo.accept()
                with conn:
                    conn.settimeout(5)
                    conn.sendall(conn.recv(1024))
            except OSError as exc:
                errors.append(str(exc))
        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        result = run(ssh + ['-W', f'127.0.0.1:{echo.getsockname()[1]}', 'root@127.0.0.1'], stdin='P0_FORWARD_OK\n')
        thread.join(timeout=10)
        result['echo_matches'] = result['stdout'] == 'P0_FORWARD_OK\n'
        result['server_errors'] = errors
        record('direct_tcpip_forward', result)
    server.terminate()
    server.wait(timeout=5)
    record('standalone_stop', {'exit': server.returncode})


def main():
    os.umask(0o077)
    try:
        # Keep test private keys outside the exported project tree.
        with tempfile.TemporaryDirectory(prefix='rikkahub-ssh-p0-', dir='/root') as directory:
            try:
                probe(Path(directory))
            finally:
                for proc in reversed(CHILDREN):
                    if proc.poll() is None:
                        proc.terminate()
                        try:
                            proc.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            proc.kill()
                            proc.wait(timeout=5)
                    if proc.stderr:
                        tail = proc.stderr.read(65536)
                        REPORT['server_log_tail'] = tail
                        (LOGS / 'sshd.log').write_text(tail)
    except Exception as exc:
        REPORT['exception'] = f'{type(exc).__name__}: {exc}'
        print(REPORT['exception'], flush=True)
    finally:
        try:
            with socket.create_connection(('127.0.0.1', 2222), timeout=1):
                record('port_released', {'passed': False, 'reason': 'listener still reachable'})
        except ConnectionRefusedError:
            record('port_released', {'passed': True})
        except OSError as exc:
            record('port_released', {'passed': False, 'reason': str(exc)})
        REPORT['kotlin_handle_cleanup'] = 'NOT TESTED: standalone subprocess is not WorkspaceBackgroundCommand'
        (LOGS / 'p0-result.json').write_text(json.dumps(REPORT, ensure_ascii=False, indent=2) + '\n')
    tests = REPORT['tests']
    required = ('configuration', 'public_key_command', 'forced_pty', 'git_init',
                'git_status', 'sftp_roundtrip', 'direct_tcpip_forward', 'standalone_stop')
    passed = all(tests.get(name, {}).get('exit') == 0 for name in required)
    passed = passed and tests.get('listener', {}).get('ready', False)
    passed = passed and tests.get('sftp_roundtrip', {}).get('content_matches', False)
    passed = passed and tests.get('direct_tcpip_forward', {}).get('echo_matches', False)
    passed = passed and tests.get('port_released', {}).get('passed', False)
    return 0 if passed and 'exception' not in REPORT else 1


if __name__ == '__main__':
    raise SystemExit(main())
