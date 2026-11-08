package me.rerere.rikkahub.ssh

/** Used only by the pwsh integration runner to execute the exact production-generated Node program. */
object IpcProbeProgramEmitter {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        print(ipcNodeProbeProgram(args[0]))
    }
}
