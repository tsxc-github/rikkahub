"""Compile actual P1 config/controller/tests, without Android stubs or a mirrored implementation."""
import pathlib, subprocess
root = pathlib.Path('/mnt/coding/rikkahub-build')
repo = root / 'src/rikkahub-ssh-p0'
cache = root / 'gradle-home/caches/modules-2/files-2.1'
def jar(group, artifact, version):
    files = sorted((cache / group / artifact / version).glob('*/*.jar'))
    if not files: raise RuntimeError('Missing cached dependency: ' + artifact + ':' + version)
    return str(files[-1])
compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.21')
libs = [jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21'),
        jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.21'),
        jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.21'),
        jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
        jar('org.jetbrains', 'annotations', '23.0.0'),
        jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
cp = ':'.join(libs)
output = root / 'logs/ssh-p1/core-classes'
output.mkdir(parents=True, exist_ok=True)
source = repo / 'app/src/main/java/me/rerere/rikkahub/ssh'
sources = [source/'SshDevConfig.kt', source/'SshDevController.kt', repo/'app/src/test/java/me/rerere/rikkahub/ssh/SshDevTest.kt']
java = '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
subprocess.run([java, '-cp', compiler+':'+cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(output), *map(str,sources)], check=True)
subprocess.run([java, '-cp', str(output)+':'+cp, 'org.junit.runner.JUnitCore', 'me.rerere.rikkahub.ssh.SshDevConfigTest', 'me.rerere.rikkahub.ssh.SshDevControllerTest'], check=True)
