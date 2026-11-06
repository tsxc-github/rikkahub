package me.rerere.rikkahub.ssh

import android.os.Build
import android.os.FileObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceBackgroundCommand
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Explicit ADB-only P0 probe, not a production SSH service or foreground service. */
@RunWith(AndroidJUnit4::class)
class SshP0DeviceTest {
    @Test
    fun sshLifecycle() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".sshprobe")) { "Use only the isolated sshprobe debug APK" }
        val report = File(context.filesDir, "ssh-p0-report.txt")
        report.writeText("P0 STARTED; not yet passed\nAndroid=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT} ABI=${Build.SUPPORTED_ABIS.joinToString()}\nBaseline=068dea4917e534cb3ed186ae963df98e66c084a4\n")
        fun note(message: String) { report.appendText("$message\n") }
        val koin = GlobalContext.get()
        val repository = koin.get<WorkspaceRepository>()
        val manager = koin.get<WorkspaceManager>()
        val workspaceId = requireNotNull(args.getString("workspaceId")) { "Specify -e workspaceId UUID" }
        val workspace = requireNotNull(repository.getById(workspaceId)) { "Workspace not found" }
        check(!workspace.isSsh() && workspace.type == "LOCAL") { "Only LOCAL workspaces allowed" }
        check(workspace.shellStatus == WorkspaceShellStatus.READY.name && manager.hasRootfs(workspace.root)) { "Rootfs must be READY" }
        val computer = args.getString("mode") == "computer"
        val id = "ssh-p0-${UUID.randomUUID()}"
        val privateDir = "/root/.$id"
        val demo = "/workspace/.$id"
        val demoHost = File(manager.filesDir(workspace.root), ".$id")
        val hostPrivateDir = File(manager.linuxDir(workspace.root), "root/.$id")
        val pool = Executors.newCachedThreadPool()
        var server: WorkspaceBackgroundCommand? = null
        var activeClient: WorkspaceBackgroundCommand? = null
        val port = 2222
        val handles = mutableListOf<WorkspaceBackgroundCommand>()
        val exited = mutableSetOf<WorkspaceBackgroundCommand>()
        fun command(script: String, input: ByteArray? = null): String {
            val result = manager.executeCommand(workspace.root, "set -eu\n$script", timeoutMillis = 20_000, stdin = input, shellCompatibilityMode = workspace.shellCompatibilityMode)
            check(result.exitCode == 0 && !result.timedOut) { "Command failed (${result.exitCode}): ${result.stderr.takeLast(4096)}" }
            return result.stdout
        }
        fun background(script: String, suffix: String): WorkspaceBackgroundCommand = manager.startBackgroundCommand(
            root = workspace.root, command = script, cwd = "", id = "$id-$suffix", shellCompatibilityMode = workspace.shellCompatibilityMode,
        ).also { handles.add(it) }
        fun output(handle: WorkspaceBackgroundCommand): String = handle.readOutput(0, 64 * 1024).toString(Charsets.UTF_8)
        fun awaitOutput(handle: WorkspaceBackgroundCommand, expected: String) {
            val logFile = File(manager.linuxDir(workspace.root), handle.logPath.removePrefix("/"))
            val latch = CountDownLatch(1)
            fun checkLog() { if (output(handle).contains(expected)) latch.countDown() }
            @Suppress("DEPRECATION")
            val observer = object : FileObserver(logFile.parentFile!!.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.CREATE) {
                override fun onEvent(event: Int, path: String?) { if (path == logFile.name) checkLog() }
            }
            observer.startWatching()
            try {
                checkLog()
                check(latch.await(12, TimeUnit.SECONDS)) { "Missing $expected; log: ${output(handle)}" }
            } finally { observer.stopWatching() }
        }
        val ssh = "ssh -F /dev/null -i $privateDir/client -p $port -o BatchMode=yes -o IdentitiesOnly=yes -o IdentityAgent=none -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$privateDir/known_hosts -o GlobalKnownHostsFile=/dev/null -o ConnectTimeout=5"
        try {
            // Test binaries must already be installed. No package manager or account modification.
            command("command -v sshd ssh ssh-keygen sftp git bash >/dev/null")
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
            command("umask 077; mkdir $privateDir; mkdir $demo; ssh-keygen -q -t ed25519 -N '' -f $privateDir/host; ssh-keygen -q -t ed25519 -N '' -f $privateDir/client; cp $privateDir/client.pub $privateDir/authorized_keys; chmod 600 $privateDir/authorized_keys")
            if (computer) {
                val encoded = requireNotNull(args.getString("publicKeyBase64")) { "Computer mode requires publicKeyBase64 (PUBLIC key only)" }
                val text = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8).trimEnd('\n')
                check(text.none { it.isISOControl() }) { "Public key must be one line without control characters" }
                val parts = text.split(Regex(" +"))
                check(parts.size >= 2 && parts[0] == "ssh-ed25519") { "P0 accepts only an Ed25519 public key" }
                check(Base64.getDecoder().decode(parts[1]).size == 51) { "Invalid Ed25519 key blob" }
                File(hostPrivateDir, "authorized_keys").appendText("${parts[0]} ${parts[1]}\n")
                command("ssh-keygen -lf $privateDir/authorized_keys >/dev/null")
            }
            val config = """
                Port $port
                ListenAddress 127.0.0.1
                HostKey $privateDir/host
                PidFile $privateDir/sshd.pid
                PermitRootLogin prohibit-password
                PubkeyAuthentication yes
                AuthenticationMethods publickey
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                PermitEmptyPasswords no
                UsePAM no
                AuthorizedKeysFile $privateDir/authorized_keys
                StrictModes yes
                AllowTcpForwarding yes
                AllowStreamLocalForwarding yes
                AllowAgentForwarding no
                GatewayPorts no
                X11Forwarding no
                PermitTunnel no
                Subsystem sftp internal-sftp
                LogLevel VERBOSE
            """.trimIndent() + "\n"
            File(hostPrivateDir, "sshd_config").writeText(config)
            command("chmod 600 $privateDir/sshd_config; sshd -t -f $privateDir/sshd_config")
            command("awk '{print \"[127.0.0.1]:$port \" \$1 \" \" \$2}' $privateDir/host.pub > $privateDir/known_hosts")
            note("HOST FINGERPRINT: ${command("ssh-keygen -lf $privateDir/host.pub").trim()}")
            note("ENVIRONMENT: ${command("uname -a; ssh -V 2>&1; ldd --version 2>&1 || true").trim()}")
            val daemon = background("exec /usr/sbin/sshd -D -e -f $privateDir/sshd_config", "server")
            server = daemon
            val serverExit = pool.submit<Int> { daemon.waitFor() }
            awaitOutput(daemon, "Server listening on")
            assertFalse("sshd exited after listen", serverExit.isDone)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
                socket.soTimeout = 3000
                check(socket.getInputStream().bufferedReader().readLine().startsWith("SSH-2.0-"))
            }
            assertTrue(command("$ssh root@127.0.0.1 'cd /workspace; test -d /skills && printf P0_AUTH_OK'").contains("P0_AUTH_OK"))
            note("PASS public-key command and /skills visibility")
            assertTrue(command("$ssh -tt root@127.0.0.1 'test -t 0 && printf P0_PTY_OK'").contains("P0_PTY_OK"))
            note("PASS forced PTY (manual interactive terminal still required)")
            command("git init -q $demo/repo; $ssh root@127.0.0.1 'git -C $demo/repo status --porcelain'")
            note("PASS git status")
            command("printf reviewed-on-pc > $privateDir/upload.txt")
            val sftp = ssh.replaceFirst("ssh ", "sftp ").replace(" -p ", " -P ")
            command("$sftp -b - root@127.0.0.1", "put $privateDir/upload.txt $demo/upload.txt\nrename $demo/upload.txt $demo/renamed.txt\nget $demo/renamed.txt $privateDir/download.txt\n".toByteArray())
            assertEquals("reviewed-on-pc", File(demoHost, "renamed.txt").readText())
            command("cmp $privateDir/upload.txt $privateDir/download.txt")
            note("PASS SFTP put/rename/get and immediate Android-side visibility")
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { echo ->
                echo.soTimeout = 10_000
                val message = "P0_FORWARD_OK\n".toByteArray()
                val echoTask = pool.submit {
                    echo.accept().use { connection ->
                        connection.soTimeout = 5000
                        val received = ByteArray(message.size)
                        DataInputStream(connection.getInputStream()).readFully(received)
                        connection.getOutputStream().write(received)
                    }
                }
                assertEquals(message.toString(Charsets.UTF_8), command("$ssh -W 127.0.0.1:${echo.localPort} root@127.0.0.1", message))
                echoTask.get(12, TimeUnit.SECONDS)
            }
            note("PASS direct-tcpip forward")
            if (computer) {
                val stop = CountDownLatch(1)
                @Suppress("DEPRECATION")
                val observer = object : FileObserver(demoHost.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.CREATE) {
                    override fun onEvent(event: Int, path: String?) { if (path == "STOP") stop.countDown() }
                }
                observer.startWatching()
                try {
                    note("READY FOR COMPUTER: loopback $port; directory=$demo; timeout=15min; create $demo/STOP via SSH to stop")
                    check(stop.await(15, TimeUnit.MINUTES)) { "Computer test timed out; cleanup still runs" }
                    val reviewed = File(demoHost, "computer.txt")
                    check(reviewed.isFile && reviewed.readText() == "reviewed-on-pc\n") { "Computer SFTP edit missing or unexpected content" }
                    note("PASS computer-created file immediately visible; operator must record manual SSH/SFTP/PTY/forward results separately")
                } finally { observer.stopWatching() }
            }
            val client = background("exec $ssh root@127.0.0.1 'printf P0_ACTIVE; exec tail -f /dev/null'", "active-client")
            activeClient = client
            val clientExit = pool.submit<Int> { client.waitFor() }
            awaitOutput(client, "P0_ACTIVE")
            val daemonLog = File(manager.linuxDir(workspace.root), daemon.logPath.removePrefix("/"))
            val pidFile = File(daemonLog.parentFile, "$id-server.pid")
            val tracerPid = pidFile.readText().trim().split(' ')[1]
            val tracees = File("/proc").listFiles().orEmpty().mapNotNull { proc ->
                val status = runCatching { File(proc, "status").readText() }.getOrNull() ?: return@mapNotNull null
                if (status.lineSequence().none { it.startsWith("TracerPid:") && it.substringAfter(':').trim() == tracerPid }) return@mapNotNull null
                val identity = runCatching { File(proc, "stat").readText().substringAfterLast(')').trim().split(' ')[19] }.getOrNull() ?: return@mapNotNull null
                proc to identity
            }
            check(tracees.isNotEmpty()) { "Cannot observe PRoot tracees; cleanup cannot be certified" }
            pool.submit { daemon.kill() }.get(15, TimeUnit.SECONDS)
            serverExit.get(5, TimeUnit.SECONDS)
            exited.add(daemon)
            clientExit.get(10, TimeUnit.SECONDS)
            exited.add(client)
            // Binding proves that the listener is gone; checking known process start-times
            // detects survivors even if PRoot stopped tracing them. No PID-only assertions.
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
            val survivors = tracees.filter { (proc, identity) ->
                runCatching { File(proc, "stat").readText().substringAfterLast(')').trim().split(' ')[19] == identity }.getOrDefault(false)
            }
            check(survivors.isEmpty()) { "PRoot children survived: ${survivors.map { it.first.name }}" }
            note("PASS actual WorkspaceBackgroundCommand.kill(), active client disconnected, port released, observed tracees gone")
            command("printf P0_AGENT_STILL_WORKS")
            note("P0 AUTOMATED CHECKS PASSED; manual computer checks required before declaring full P0 passed")
        } catch (error: Throwable) {
            note("FAIL ${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            // Preserve logs, never private keys. Do not mask a failed cleanup with a green report.
            server?.let { note("SSHD LOG TAIL:\n${output(it).takeLast(8192)}") }
            try {
                listOfNotNull(server, activeClient).filterNot { it in exited }.forEach { handle ->
                    runCatching { pool.submit { handle.kill() }.get(15, TimeUnit.SECONDS) }
                        .onFailure { note("CLEANUP ERROR: ${it.javaClass.simpleName}") }
                }
            } finally {
                runCatching { command("rm -rf $privateDir $demo") }
                    .onFailure { note("REMOVE TEST DATA FAILED: ${it.javaClass.simpleName}") }
                handles.forEach { handle ->
                    val logFile = File(manager.linuxDir(workspace.root), handle.logPath.removePrefix("/"))
                    logFile.delete()
                    File(logFile.parentFile, logFile.name.removeSuffix(".log") + ".pid").delete()
                }
                pool.shutdownNow()
            }
        }
    }
}
