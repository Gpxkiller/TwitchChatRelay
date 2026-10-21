"""Start the packaged relay with synthetic keys; empty channels never contact Twitch."""
import concurrent.futures, json, os, secrets, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error
from pathlib import Path

def smoke(jar):
 with socket.socket() as probe:probe.bind(('127.0.0.1',28765))
 with tempfile.TemporaryDirectory() as directory:
  root=Path(directory);os.chmod(root,0o700)
  def write(name,row):
   path=root/name;path.write_text(json.dumps(row));os.chmod(path,0o600)
  write('bridge.json',{'enabled':True,'clientId':'exampleclient','login':'exampleviewer'})
  keys={name:secrets.token_hex(32) for name in ('survival','arena')};write('relay-keys.json',keys)
  proc=subprocess.Popen(['java','-cp',str(Path(jar).resolve()),'fr.gpxkiller.twitch.TwitchBridge',str(root)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  try:
   for _ in range(100):
    if proc.poll() is not None:raise AssertionError('Packaged relay failed to start')
    try:
     with socket.create_connection(('127.0.0.1',28765),timeout=.1):break
    except OSError:time.sleep(.1)
   else:raise AssertionError('Relay listen timeout')
   def poll(mode):
    request=urllib.request.Request('http://127.0.0.1:28765/v1/poll',data=b'{"channels":[],"after":-1,"epoch":""}',headers={'X-Twitch-Relay':keys[mode],'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=5) as response:row=json.load(response)
    assert row['mode']==mode and row['messages']==[]
    assert not any(k in row for k in ('access_token','refresh_token'))
   with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(poll,['survival','arena']*25))
   assert not (root/'tokens.json').exists()
   print('Packaged bridge: 50 HTTP polls, two isolated backend identities, no OAuth credentials/network required: PASS')
  finally:
   proc.terminate()
   try:proc.communicate(timeout=10)
   except subprocess.TimeoutExpired:proc.kill();proc.communicate(timeout=5)
if __name__=='__main__':smoke(sys.argv[1])
