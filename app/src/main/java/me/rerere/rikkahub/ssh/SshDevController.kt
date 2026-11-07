package me.rerere.rikkahub.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal data class PreparedSsh(val name: String, val fingerprint: String, val token: String)
internal interface RunningSsh {
    val alive: Boolean
    suspend fun awaitReady()
    suspend fun awaitExit(): Int
    /** Must confirm exit and release, or throw. Retain ownership when this fails. */
    suspend fun stopAndWait()
}
internal interface SshDevRuntime {
    suspend fun recover()
    suspend fun prepare(config: SshDevConfig): PreparedSsh
    suspend fun launch(config: SshDevConfig, prepared: PreparedSsh): RunningSsh
    suspend fun clearLease()
}

/** No Conversation owner. The service and notification observe, but do not own, this state. */
internal class SshDevController(private val runtime: SshDevRuntime, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val stopRequested = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(SshDevState())
    val state: StateFlow<SshDevState> = mutableState.asStateFlow()
    private var running: RunningSsh? = null
    private var activeConfig: SshDevConfig? = null

    suspend fun initialize() = mutex.withLock {
        try {
            runtime.recover()
        } catch (e: Exception) {
            mutableState.value = SshDevState(phase = SshDevPhase.ERROR, message = e.message ?: "需要检查上次 SSH 进程")
        }
    }

    suspend fun start(input: SshDevConfig) {
        stopRequested.set(false)
        mutex.withLock {
            val config = try { input.validated() } catch (e: IllegalArgumentException) {
                // Bad input must never turn an existing live server into a false STOPPED/ERROR state.
                if (running == null) mutableState.value = SshDevState(phase = SshDevPhase.ERROR, message = e.message ?: "配置无效")
                return@withLock
            }
            if (running?.alive == true && activeConfig == config && state.value.phase == SshDevPhase.RUNNING) return@withLock
            if (!stopLocked()) return@withLock
            mutableState.value = SshDevState(phase = SshDevPhase.STARTING, workspaceId = config.workspaceId, port = config.port, message = "正在检查环境与配置",
                listenMode = config.listenMode, bindAddress = config.bindAddress, bindInterface = config.bindInterface)
            try {
                val prepared = withTimeout(45_000) {
                    runtime.recover()
                    runtime.prepare(config)
                }
                check(!stopRequested.get()) { "启动已取消" }
                // Even cancellation between native Process.start and dispatcher resumption must not lose its handle.
                val handle = withContext(NonCancellable) { runtime.launch(config, prepared).also { running = it; activeConfig = config } }
                coroutineContext.ensureActive()
                withTimeout(10_000) { handle.awaitReady() }
                check(!stopRequested.get()) { "启动已取消" }
                mutableState.value = state.value.copy(phase = SshDevPhase.RUNNING, workspaceName = prepared.name,
                    fingerprint = prepared.fingerprint, message = if (config.listenMode == SshListenMode.LOOPBACK) "仅回环 127.0.0.1；通过 USB ADB 转发连接" else "局域网 ${config.bindAddress} · ${config.bindInterface}；电脑可直连，网络变化将停止",
                    startedAt = System.currentTimeMillis(), ownsProcess = true)
                scope.launch {
                    val result = runCatching { handle.awaitExit() }
                    mutex.withLock {
                        if (running !== handle) return@withLock
                        if (stopLocked()) mutableState.value = mutableState.value.copy(phase = SshDevPhase.ERROR,
                            message = "SSH 进程已退出（${result.getOrNull() ?: "未知"}），不会自动重启")
                    }
                }
            } catch (e: Exception) {
                val cleaned = withContext(NonCancellable) { stopLocked() }
                if (cleaned) mutableState.value = state.value.copy(phase = if (stopRequested.get()) SshDevPhase.STOPPED else SshDevPhase.ERROR,
                    message = if (stopRequested.get()) "已取消启动" else e.message ?: "SSH 启动失败", ownsProcess = false)
                if (e is CancellationException) throw e
            }
        }
    }

    suspend fun stop(reason: String = "已停止") {
        stopRequested.set(true)
        mutex.withLock {
            if (stopLocked()) mutableState.value = state.value.copy(phase = SshDevPhase.STOPPED, message = reason, ownsProcess = false, startedAt = null)
        }
    }

    /** Called only with mutex held. Never forget a possibly live process when cleanup fails. */
    private suspend fun stopLocked(): Boolean {
        val handle = running
        if (handle != null) mutableState.value = state.value.copy(phase = SshDevPhase.STOPPING, message = "正在关闭连接并清理 PRoot")
        return try {
            if (handle != null) withTimeout(15_000) { handle.stopAndWait() }
            else runtime.recover()
            runtime.clearLease()
            running = null
            activeConfig = null
            mutableState.value = state.value.copy(ownsProcess = false, startedAt = null)
            true
        } catch (e: Exception) {
            mutableState.value = state.value.copy(phase = SshDevPhase.ERROR, message = e.message ?: "停止失败，请重试清理", ownsProcess = handle != null)
            false
        }
    }

    suspend fun reportServiceFailure(message: String) {
        stop(message)
        mutex.withLock {
            mutableState.value = state.value.copy(phase = SshDevPhase.ERROR, message = message)
        }
    }
}
