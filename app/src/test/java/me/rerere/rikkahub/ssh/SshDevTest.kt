package me.rerere.rikkahub.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class SshDevConfigTest {
    private fun key(bytes: Int = 32, algorithm: String = "ssh-ed25519"): String {
        val name = algorithm.toByteArray()
        val data = ByteBuffer.allocate(8 + name.size + bytes).putInt(name.size).put(name).putInt(bytes).put(ByteArray(bytes) { (it + 1).toByte() }).array()
        return "ssh-ed25519 ${Base64.getEncoder().encodeToString(data)}"
    }
    private val id = "f7f0dbfb-11aa-4fbb-afbc-e43d433e131f"
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }
    @Test fun validKeyIsNormalized() { assertEquals(key(), normalizePublicKey("  ${key()} computer-name  ")) }
    @Test fun commentIsNotIdentity() { rejects { SshDevConfig(id, 2222, listOf(key() + " first", key() + " second")).validated() } }
    @Test fun privateKeyRejected() { rejects { normalizePublicKey("-----BEGIN OPENSSH PRIVATE KEY-----") } }
    @Test fun newlinesRejected() { rejects { normalizePublicKey(key() + "\ncommand=x") } }
    @Test fun carriageReturnRejected() { rejects { normalizePublicKey(key() + "\r") } }
    @Test fun tabRejected() { rejects { normalizePublicKey(key().replace(' ', '\t')) } }
    @Test fun authorizedKeyOptionsRejected() { rejects { normalizePublicKey("command=evil " + key()) } }
    @Test fun malformedBase64Rejected() { rejects { normalizePublicKey("ssh-ed25519 ###") } }
    @Test fun shortKeyRejected() { rejects { normalizePublicKey(key(31)) } }
    @Test fun wrongWireAlgorithmRejected() { rejects { normalizePublicKey(key(32, "ssh-rsa")) } }
    @Test fun emptyKeysRejected() { rejects { SshDevConfig(id, 2222, emptyList()).validated() } }
    @Test fun excessiveKeysRejected() { rejects { SshDevConfig(id, 2222, List(21) { key() }).validated() } }
    @Test fun invalidPortsRejected() { listOf(-1, 0, 22, 1023, 65536).forEach { port -> rejects { SshDevConfig(id, port, listOf(key())).validated() } } }
    @Test fun portBoundariesAccepted() { listOf(1024, 65535).forEach { SshDevConfig(id, it, listOf(key())).validated() } }
    @Test fun uuidInjectionRejected() { rejects { SshDevConfig("../other; echo x", 2222, listOf(key())).validated() } }
}

class SshDevControllerTest {
    private class Server : RunningSsh {
        var living = true
        var stops = 0
        var refuseStop = false
        var readyGate: CompletableDeferred<Unit>? = null
        val exited = CompletableDeferred<Int>()
        override val alive get() = living
        override suspend fun awaitReady() { readyGate?.await() }
        override suspend fun awaitExit() = exited.await()
        override suspend fun stopAndWait() {
            stops++
            check(!refuseStop) { "cannot confirm process exit" }
            living = false
            exited.complete(0)
        }
    }
    private class Runtime : SshDevRuntime {
        var recovered = 0
        var cleared = 0
        var launched = 0
        var failRecovery = false
        var failPrepare = false
        var prepareGate: CompletableDeferred<Unit>? = null
        val servers = mutableListOf<Server>()
        var next = Server()
        override suspend fun recover() { recovered++; check(!failRecovery) { "orphan remains" } }
        override suspend fun clearLease() { cleared++ }
        override suspend fun prepare(config: SshDevConfig): PreparedSsh {
            prepareGate?.await()
            check(!failPrepare) { "missing sshd" }
            return PreparedSsh("test", "SHA256:test", "ssh-dev-test")
        }
        override suspend fun launch(config: SshDevConfig, prepared: PreparedSsh): RunningSsh {
            launched++
            return next.also { servers += it; next = Server() }
        }
    }
    private val blob = Base64.getEncoder().encodeToString(ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".toByteArray()).putInt(32).put(ByteArray(32) { 1 }).array())
    private fun config(port: Int = 2222) = SshDevConfig("f7f0dbfb-11aa-4fbb-afbc-e43d433e131f", port, listOf("ssh-ed25519 $blob"))
    private fun scenario(block: suspend CoroutineScope.(SshDevController, Runtime) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = Runtime()
        val controller = SshDevController(runtime, scope)
        try { controller.initialize(); block(controller, runtime) } finally { scope.cancel() }
    }
    @Test fun defaultOffNoLaunch() = scenario { c, r -> assertEquals(SshDevPhase.STOPPED, c.state.value.phase); assertEquals(0, r.launched) }
    @Test fun startRequiresReadiness() = scenario { c, r ->
        val gate = CompletableDeferred<Unit>(); r.next.readyGate = gate
        val job = launch { c.start(config()) }; yield()
        assertEquals(SshDevPhase.STARTING, c.state.value.phase)
        gate.complete(Unit); job.join()
        assertEquals(SshDevPhase.RUNNING, c.state.value.phase); assertTrue(c.state.value.ownsProcess)
    }
    @Test fun duplicateStartIdempotent() = scenario { c, r -> c.start(config()); c.start(config()); assertEquals(1, r.launched) }
    @Test fun switchStopsOldBeforeLaunch() = scenario { c, r -> c.start(config()); val first = r.servers.single(); c.start(config(2223)); assertFalse(first.alive); assertEquals(2, r.launched) }
    @Test fun explicitStopClosesProcess() = scenario { c, r -> c.start(config()); c.stop(); assertFalse(r.servers.single().alive); assertEquals(SshDevPhase.STOPPED, c.state.value.phase); assertFalse(c.state.value.ownsProcess) }
    @Test fun repeatedStopSafe() = scenario { c, r -> c.start(config()); c.stop(); c.stop(); assertEquals(1, r.servers.single().stops) }
    @Test fun failedStopKeepsOwnershipAndCanRetry() = scenario { c, r ->
        c.start(config()); val server = r.servers.single(); server.refuseStop = true
        c.stop(); assertEquals(SshDevPhase.ERROR, c.state.value.phase); assertTrue(c.state.value.ownsProcess)
        server.refuseStop = false; c.stop(); assertEquals(SshDevPhase.STOPPED, c.state.value.phase)
    }
    @Test fun cannotReplaceUncleanProcess() = scenario { c, r ->
        c.start(config()); r.servers.single().refuseStop = true; c.start(config(2223))
        assertEquals(1, r.launched); assertEquals(SshDevPhase.ERROR, c.state.value.phase)
    }
    @Test fun missingDependencyFailsWithoutListening() = scenario { c, r -> r.failPrepare = true; c.start(config()); assertEquals(0, r.launched); assertEquals(SshDevPhase.ERROR, c.state.value.phase) }
    @Test fun staleLeaseIsFailClosed() = scenario { c, r -> r.failRecovery = true; c.start(config()); assertEquals(0, r.launched); assertEquals(SshDevPhase.ERROR, c.state.value.phase) }
    @Test fun noAutomaticRestartAfterExit() = scenario { c, r ->
        c.start(config()); r.servers.single().living = false; r.servers.single().exited.complete(7); yield()
        assertEquals(SshDevPhase.ERROR, c.state.value.phase); assertEquals(1, r.launched); assertFalse(c.state.value.ownsProcess)
    }
    @Test fun invalidNewConfigDoesNotHideLiveServer() = scenario { c, r ->
        c.start(config()); c.start(config().copy(publicKeys = emptyList()))
        assertEquals(SshDevPhase.RUNNING, c.state.value.phase); assertTrue(r.servers.single().alive)
    }
    @Test fun cancellationAfterLaunchCleansOwnedHandle() = scenario { c, r ->
        r.next.readyGate = CompletableDeferred()
        val job = launch { c.start(config()) }; yield(); assertEquals(1, r.launched)
        job.cancelAndJoin(); assertFalse(r.servers.single().alive); assertFalse(c.state.value.ownsProcess)
    }
    @Test fun recoveryFailureNeverClaimsStopped() = scenario { c, r ->
        r.failRecovery = true; c.stop()
        assertEquals(SshDevPhase.ERROR, c.state.value.phase)
        r.failRecovery = false; c.stop(); assertEquals(SshDevPhase.STOPPED, c.state.value.phase)
    }
    @Test fun serviceStartRefusalDoesNotLeaveListener() = scenario { c, r ->
        c.start(config()); c.reportServiceFailure("FGS denied")
        assertEquals(SshDevPhase.ERROR, c.state.value.phase); assertFalse(r.servers.single().alive)
    }
    @Test fun stopDuringPreparePreventsLaunch() = scenario { c, r ->
        val gate = CompletableDeferred<Unit>(); r.prepareGate = gate
        val start = launch { c.start(config()) }; yield()
        val stop = launch { c.stop() }; yield()
        gate.complete(Unit); start.join(); stop.join()
        assertEquals(0, r.launched); assertEquals(SshDevPhase.STOPPED, c.state.value.phase)
    }
}
