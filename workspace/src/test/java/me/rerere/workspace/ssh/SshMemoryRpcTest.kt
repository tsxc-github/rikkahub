package me.rerere.workspace.ssh

import kotlinx.serialization.json.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.UUID

class SshMemoryRpcTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun completeLibraryAndConflictsTravelOverRealSshWithoutShellTruncation() {
        val helper = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "tools/memory-sync/memory_sync.py") }.first { it.isFile }
        val data = temp.newFolder("remote-data")
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(temp.root, "host-key").toPath())
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "fixture" && password == "fixture-password" }
            commandFactory = CommandFactory { channel, command ->
                ProcessShellFactory(command, "/bin/sh", "-c", command).createShell(channel)
            }
            start()
        }
        val pool = SshConnectionPool()
        try {
            var config = SshConnectionConfig(host = "127.0.0.1", port = server.port, username = "fixture",
                authType = SshAuthType.PASSWORD, password = "fixture-password", remoteDir = data.absolutePath)
            val key = assertThrows(SshUnknownHostKeyException::class.java) { pool.probe(config) }.hostKey
            config = config.copy(hostKey = key)
            val space = UUID.randomUUID().toString()
            fun request(op: String, path: String? = null, token: String? = null, content: String? = null): JsonObject {
                val json = buildJsonObject {
                    put("protocol", 1); put("op", op); put("spaceId", space)
                    path?.let { put("path", it) }; token?.let { put("expectedToken", it) }
                    content?.let { put("content", it) }
                    if (op == "put" || op == "delete") put("operationId", UUID.randomUUID().toString())
                }
                val quote: (String) -> String = { "'" + it.replace("'", "'\"'\"'") + "'" }
                val command = "python3 ${quote(helper.absolutePath)} rpc --root ${quote(data.absolutePath)}"
                val response = pool.exchangeJson("memory-fixture", config, command, (json.toString() + "\n").toByteArray())
                return Json.parseToJsonElement(response).jsonObject
            }
            assertTrue(request("create")["ok"]!!.jsonPrimitive.boolean)
            val content = "原文[stated]\r\n".repeat(2000)
            repeat(5) { i ->
                val result = request("put", "/topics/fixture-$i.md", "0", content)
                assertTrue(result.toString(), result["ok"]!!.jsonPrimitive.boolean)
            }
            val listed = request("list")["documents"]!!.jsonArray
            assertEquals(5, listed.size)
            for (doc in listed) assertEquals(content, doc.jsonObject["content"]!!.jsonPrimitive.content)
            val conflict = request("put", "/topics/fixture-0.md", "0", "stale overwrite")
            assertFalse(conflict["ok"]!!.jsonPrimitive.boolean)
            assertEquals("conflict", conflict["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals(content, conflict["document"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        } finally { pool.close(); server.stop(true) }
    }

    @Test fun malformedUtf8AndOverBudgetFramesAreRejectedRatherThanPartiallyParsed() {
        assertThrows(CharacterCodingException::class.java) {
            RpcCollector(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), 16).finish(1_000)
        }
        assertThrows(IOException::class.java) {
            RpcCollector(ByteArrayInputStream(ByteArray(33)), 32).finish(1_000)
        }
    }
}
