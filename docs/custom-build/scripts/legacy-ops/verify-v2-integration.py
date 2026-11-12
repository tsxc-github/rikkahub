"""Fast integration typecheck against real cached modules; NOT a replacement for Gradle APK build."""
import pathlib, runpy, subprocess
v=runpy.run_path('/mnt/coding/rikkahub-build/verify-v2-core.py')
repo=v['repo'];root=v['root'];cache=v['cache'];java=v['java']
classes=list(repo.glob('*/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'))
classes+=list(repo.glob('app/build/intermediates/built_in_kotlinc/githubDebug/compileGithubDebugKotlin/classes'))
classes+=list(repo.glob('*/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'))
# Explicit dependencies first; include the rest for application/Android/Koin interfaces.
jars=list(cache.glob('*/*/*/*/*.jar'))
jars+=list(repo.glob('app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/githubDebug/**/R.jar'))
cp=v['cp']+':'+':'.join(map(str,classes+jars))
files=[*v['sources'][:-2],
repo/'app/src/main/java/me/rerere/rikkahub/data/ai/tools/opsv2/WorkspaceV2Tools.kt',
repo/'app/src/main/java/me/rerere/rikkahub/data/ai/tools/opsv2/WorkspaceQueryTools.kt',
repo/'app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt',
repo/'app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt',
repo/'app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt',
repo/'app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt',
repo/'workspace/src/main/java/me/rerere/workspace/WorkspaceBackend.kt',
repo/'workspace/src/main/java/me/rerere/workspace/LocalWorkspaceBackend.kt',
repo/'workspace/src/main/java/me/rerere/workspace/ssh/SshWorkspaceBackend.kt']
out=root/'ops-v2-verification/integration-classes';out.mkdir(exist_ok=True)
# Argfile avoids the OS single-argument length limit for the cached dependency classpath.
args=['-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-Xfriend-paths='+','.join(map(str,classes)),'-d',str(out),*map(str,dict.fromkeys(files))]
argfile=root/'ops-v2-verification/integration.args'
argfile.write_text('\n'.join('"'+arg.replace('\\','\\\\').replace('"','\\"')+'"' for arg in args))
subprocess.run([java,'-cp',v['compiler']+':'+v['cp'],'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],check=True)
print('INTEGRATION TYPECHECK PASSED')
