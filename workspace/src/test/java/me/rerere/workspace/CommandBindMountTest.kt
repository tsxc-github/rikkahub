package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CommandBindMountTest {
    @get:Rule val temp = TemporaryFolder()
    private class Runner : WorkspaceShellRunner {
        var command: WorkspaceShellContext? = null
        var terminal: WorkspaceTerminalContext? = null
        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult { command=context; return WorkspaceCommandResult(0,"","") }
        override fun startBackground(context: WorkspaceShellContext,id:String): WorkspaceBackgroundCommand {
            command=context
            return object : WorkspaceBackgroundCommand {
                override val logPath="/tmp/rikkahub/background/test.log"
                override fun waitFor()=0
                override fun outputSize()=0L
                override fun readOutput(offset:Long,maxBytes:Int)=ByteArray(0)
                override fun kill() {}
            }
        }
        override fun terminalLaunch(context:WorkspaceTerminalContext):WorkspaceTerminalLaunch? {terminal=context;return null}
    }
    private fun fixture(): Triple<WorkspaceManager,Runner,WorkspaceBindMount> {
        val runner=Runner();val source=temp.newFolder()
        val manager=WorkspaceManager(temp.newFolder(),shellRunner=runner,bindMounts=listOf(WorkspaceBindMount(source,"/skills")))
        manager.ensureWorkspace("test")
        File(manager.linuxDir("test"),"vscode-tmp").mkdirs()
        return Triple(manager,runner,WorkspaceBindMount(temp.newFolder(),"/vscode-tmp",true))
    }
    @Test fun executeExtraMountDoesNotPersistToNextCommand() {
        val (manager,runner,mount)=fixture()
        manager.executeCommand("test","true",extraBindMounts=listOf(mount))
        assertEquals(listOf("/skills","/vscode-tmp"),runner.command!!.bindMounts.map {it.target})
        manager.executeCommand("test","true")
        assertEquals(listOf("/skills"),runner.command!!.bindMounts.map {it.target})
    }
    @Test fun backgroundExtraDoesNotChangeOrdinaryTerminalOrTrackingPaths() {
        val (manager,runner,mount)=fixture()
        val handle=manager.startBackgroundCommand("test","true","","probe",extraBindMounts=listOf(mount))
        assertEquals("/tmp/rikkahub/background/test.log",handle.logPath)
        assertEquals("/vscode-tmp",runner.command!!.bindMounts.last().target)
        manager.terminalLaunch("test",false)
        assertEquals(listOf("/skills"),runner.terminal!!.bindMounts.map {it.target})
        assertEquals("/tmp/rikkahub/background/test.log",handle.logPath)
    }
    @Test fun protectedTargetsAndNestedAliasesRejected() {
        val (manager,_,mount)=fixture()
        for(target in listOf("/","/tmp","/tmp/x","/workspace","/workspace/x","/proc","/dev","/sys","/skills","/skills/x")) {
            assertThrows(target,IllegalArgumentException::class.java) {manager.executeCommand("test","true",extraBindMounts=listOf(mount.copy(target=target)))}
        }
    }
    @Test fun dotDotDoubleSlashAndControlTargetsRejected() {
        val (manager,_,mount)=fixture()
        for(target in listOf("/vscode-tmp/../tmp","//tmp","/vscode-tmp//child","/vscode-tmp\n")) {
            assertThrows(IllegalArgumentException::class.java) {manager.executeCommand("test","true",extraBindMounts=listOf(mount.copy(target=target)))}
        }
    }
    @Test fun overlappingExtraMountsRejected() {
        val (manager,_,mount)=fixture()
        File(manager.linuxDir("test"),"vscode-tmp/child").mkdirs()
        assertThrows(IllegalArgumentException::class.java) {manager.executeCommand("test","true",extraBindMounts=listOf(mount,mount.copy(target="/vscode-tmp/child")))}
    }
    @Test fun guestMountPointMustAlreadyExist() {
        val (manager,_,mount)=fixture()
        assertThrows(IllegalArgumentException::class.java) {manager.executeCommand("test","true",extraBindMounts=listOf(mount.copy(target="/missing")))}
    }
    @Test fun sourceMustAlreadyBeDirectory() {
        val (manager,_,mount)=fixture()
        assertThrows(IllegalArgumentException::class.java) {manager.executeCommand("test","true",extraBindMounts=listOf(mount.copy(source=File(temp.root,"not-created"))))}
    }
    @Test fun symlinkGuestMountPointRejected() {
        val (manager,_,mount)=fixture()
        val guest=File(manager.linuxDir("test"),"vscode-tmp");assertTrue(guest.delete())
        Files.createSymbolicLink(guest.toPath(),temp.newFolder().toPath())
        assertThrows(IllegalArgumentException::class.java) {manager.executeCommand("test","true",extraBindMounts=listOf(mount))}
    }
    @Test fun duplicateTargetRejected() {
        val (manager,_,mount)=fixture()
        assertThrows(IllegalArgumentException::class.java) {manager.executeCommand("test","true",extraBindMounts=listOf(mount,mount))}
    }
}
