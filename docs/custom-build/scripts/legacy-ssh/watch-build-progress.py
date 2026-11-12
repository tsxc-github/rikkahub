"""Build-log monitor only, not a test runner. Background polling explicitly authorized by user."""
import json
import subprocess
import time

REMOTE = r'''
import json,pathlib
p=pathlib.Path('/mnt/coding/rikkahub-build/logs/ssh-p1')
stages={}
for f in p.glob('*exit-code'):
    stages[f.name]=f.read_text().strip()[:80]
driver=p/'driver.log'
last=driver.read_text(errors='replace').splitlines()[-5:] if driver.exists() else []
summary=(p/'summary.txt').read_text()[:1000] if (p/'summary.txt').exists() else ''
print(json.dumps({'stages':stages,'last':last,'summary':summary}))
'''
last = None
for attempt in range(121):
    try:
        result=subprocess.run(['ssh','-o','ConnectTimeout=10','coding','python3 -'],input=REMOTE,text=True,capture_output=True,timeout=20)
        if result.returncode:
            print('MONITOR SSH error:',result.stderr[-500:],flush=True)
        else:
            state=json.loads(result.stdout)
            encoded=json.dumps(state,sort_keys=True)
            if encoded!=last:
                print(time.strftime('%Y-%m-%d %H:%M:%S'),encoded,flush=True)
                last=encoded
            if 'exit-code' in state['stages']:
                print('BUILD_MONITOR_COMPLETE: driver exit='+state['stages']['exit-code'],flush=True)
                break
    except (subprocess.TimeoutExpired,ValueError) as exc:
        print('MONITOR temporary error:',type(exc).__name__,flush=True)
    if attempt==120:
        print('MONITOR_LIMIT_REACHED: no completion after about one hour',flush=True)
        raise SystemExit(2)
    time.sleep(30)
