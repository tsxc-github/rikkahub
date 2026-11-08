package me.rerere.rikkahub.ssh

/** Generated Node program is also executed by the pwsh integration check, not mirrored in a test. */
internal fun ipcNodeProbeProgram(directory: String = SSH_IPC_GUEST): String {
    require(Regex("^/[A-Za-z0-9_/-]+$").matches(directory) && !directory.contains("//")) { "Invalid probe directory" }
    return """
        const net = require('net'), crypto = require('crypto'), os = require('os');
        console.log('NODE_VERSION=' + process.version + ' TMPDIR=' + os.tmpdir());
        async function probe(label, options) {
          return new Promise(resolve => {
            const server = net.createServer(socket => socket.end('OK'));
            let done = false, listening = false, client;
            const finish = (ok, error) => {
              if (done) return; done = true; clearTimeout(timer);
              if (client) client.destroy();
              const emit = () => { console.log(JSON.stringify({label, ok, listening, code: error && error.code, message: error && error.message})); resolve(); };
              try { server.close(emit); } catch (_) { emit(); }
            };
            const timer = setTimeout(() => finish(false, {code:'TIMEOUT'}), 4000);
            server.once('error', error => finish(false, error));
            server.listen(options, () => {
              if (done) return;
              listening = true;
              const address = server.address();
              client = options.path ? net.createConnection({path:options.path}) : net.createConnection({host:'127.0.0.1',port:address.port});
              let data = '';
              client.on('data', chunk => { data += chunk.toString(); });
              client.once('error', error => finish(false, error));
              client.once('end', () => finish(data === 'OK'));
            });
          });
        }
        (async () => {
          await probe('TCP_LOOPBACK', {host:'127.0.0.1', port:0});
          await probe('UNIX_DEFAULT_TMP', {path:'/tmp/rp-' + crypto.randomBytes(4).toString('hex')});
          await probe('UNIX_SHORT_BIND', {path:'$directory/rp-' + crypto.randomBytes(4).toString('hex')});
          await probe('UNIX_VSCODE_BIND', {path:'$directory/code-' + crypto.randomUUID()});
        })().catch(error => { console.log('PROBE_ERROR=' + error.message); process.exitCode = 1; });
    """.trimIndent()
}
