"""Compile the NEW core Kotlin source against cached real ai module classes and run JUnit.
No APK build, no stub UI models, no Gradle/configuration changes. Execute on coding.
"""
import glob, os, pathlib, subprocess
root=pathlib.Path('/mnt/coding/rikkahub-build')
repo=root/'src/rikkahub'
cache=root/'gradle-home/caches/modules-2/files-2.1'
def jar(group,artifact,version=None):
    paths=list((cache/group/artifact).glob((version or '*')+'/*/*.jar'))
    if not paths:
        versions={'junit':'4.13.2','hamcrest-core':'1.3'}
        if artifact not in versions: raise RuntimeError('Missing cached dependency '+artifact)
        import urllib.request
        version=versions[artifact]
        target=root/'ops-v2-verification/lib'/f'{artifact}-{version}.jar'
        target.parent.mkdir(parents=True,exist_ok=True)
        url=f'https://maven.aliyun.com/repository/central/{group.replace(".","/")}/{artifact}/{version}/{artifact}-{version}.jar'
        urllib.request.urlretrieve(url,target)
        return str(target)
    return str(sorted(paths,key=lambda p:tuple(int(x) if x.isdigit() else x for x in __import__('re').split(r'(\d+)',str(p))))[-1])
def checked(args):
    code=subprocess.call(args)
    if code: raise SystemExit(code)
compiler=jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.21')
libs=[jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.21'),jar('org.jetbrains.kotlin','kotlin-reflect','2.3.21'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.4.21'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),jar('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.12.0'),jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.12.0'),jar('org.jetbrains','annotations','23.0.0'),jar('junit','junit'),jar('org.hamcrest','hamcrest-core')]
ai=repo/'ai/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
if not ai.is_dir(): raise RuntimeError('Real compiled ai debug classes are unavailable')
# Additional dependencies used by the real UIMessage implementation, pinned libs come first.
extra=[str(p) for group in ('org.jetbrains.kotlinx','org.jetbrains.compose.runtime','androidx.compose.runtime') for p in (cache/group).glob('*/*/*/*.jar')]
modules=[repo/'app/build/intermediates/built_in_kotlinc/githubDebug/compileGithubDebugKotlin/classes',repo/'workspace/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes']
cp=':'.join(libs+extra+[str(ai),*map(str,modules),str(root/'android-sdk/platforms/android-37.2/android.jar')])
out=root/'ops-v2-verification/core-classes';out.mkdir(parents=True,exist_ok=True)
base=repo/'app/src/main/java/me/rerere/rikkahub/data/ai/tools/opsv2'
sources=[base/'OpsPage.kt',base/'ToolResultStore.kt',base/'WorkspaceContextProjector.kt',base/'OpsQuery.kt',base/'WorkspaceV2Tools.kt',repo/'workspace/src/main/java/me/rerere/workspace/WorkspaceReadChunk.kt',repo/'app/src/test/java/me/rerere/rikkahub/data/ai/tools/opsv2/OpsV2Test.kt',repo/'app/src/test/java/me/rerere/rikkahub/data/ai/tools/opsv2/WorkspaceQueryTest.kt',repo/'app/src/test/java/me/rerere/rikkahub/data/ai/tools/opsv2/OpsPagingIntegrationTest.kt']
java='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
checked([java,'-cp',compiler+':'+cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(out),*map(str,sources)])
checked([java,'-cp',str(out)+':'+str(repo/'app/src/main/resources')+':'+cp,'org.junit.runner.JUnitCore','me.rerere.rikkahub.data.ai.tools.opsv2.OpsV2Test','me.rerere.rikkahub.data.ai.tools.opsv2.WorkspaceQueryTest','me.rerere.rikkahub.data.ai.tools.opsv2.OpsPagingIntegrationTest'])
