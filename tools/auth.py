"""Local operator Device OAuth, explicit pairing and refresh. No public web listener."""
import argparse, json, os, re, secrets, stat, tempfile, time
from pathlib import Path
import urllib.request, urllib.parse, urllib.error

SCOPE='user:read:chat'
class Refused(Exception): pass
class WrongIdentity(Refused): pass
class Remote(Refused):
 def __init__(self, reason): self.reason=reason
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args): return None

def read(path):
 path=Path(path)
 if any(p.is_symlink() for p in (path,*path.parents)):raise Refused('Linked paths refused')
 if not path.is_file() or path.stat().st_size>65536:raise Refused('Invalid private file')
 if os.name=='posix' and path.stat().st_mode&0o077:raise Refused('Private file must use 0600')
 with path.open('rb') as handle:raw=handle.read(65537)
 if len(raw)>65536:raise Refused('Oversized private file')
 return json.loads(raw)

def atomic(path, data):
 path=Path(path)
 if any(p.is_symlink() for p in (path,*path.parents)):raise Refused('Linked paths refused')
 fd,name=tempfile.mkstemp(dir=path.parent,prefix='.oauth-')
 try:
  if hasattr(os,'fchmod'):os.fchmod(fd,0o600)
  with os.fdopen(fd,'w',encoding='utf8') as out:json.dump(data,out,allow_nan=False);out.flush();os.fsync(out.fileno())
  os.replace(name,path)
 finally:
  if os.path.exists(name):os.unlink(name)

def api(endpoint, fields=None, token=None):
 if endpoint not in {'device','token','validate','revoke'}:raise Refused('Unknown endpoint')
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='OAuth '+token
 data=urllib.parse.urlencode(fields).encode() if fields is not None else None
 if data is not None:headers['Content-Type']='application/x-www-form-urlencoded'
 try:
  with urllib.request.build_opener(NoRedirect).open(urllib.request.Request('https://id.twitch.tv/oauth2/'+endpoint,data=data,headers=headers),timeout=12) as response:
   raw=response.read(65537)
   if len(raw)>65536:raise Refused('Oversized response')
   return json.loads(raw) if raw else {}
 except urllib.error.HTTPError as error:
  try:body=json.loads(error.read(65536));reason=body.get('error') or body.get('message')
  except (ValueError,AttributeError):reason='remote_error'
  allowed={'authorization_pending','slow_down','access_denied','expired_token'}
  raise Remote(reason if reason in allowed else 'remote_error') from None
 except (OSError,ValueError):raise Remote('unavailable') from None

class Auth:
 def __init__(self,directory,remote=api,clock=time.time):
  self.root=Path(directory);self.remote=remote;self.clock=clock;self.config=read(self.root/'bridge.json')
  if not re.fullmatch('[A-Za-z0-9]{8,80}',self.config.get('clientId','')) or not re.fullmatch('[a-z0-9_]{1,25}',self.config.get('login','')):raise Refused('Configure clientId and expected lowercase login')
 def validate(self,access,pinned=None):
  result=self.remote('validate',token=access)
  if not isinstance(result,dict) or result.get('client_id')!=self.config['clientId'] or result.get('login')!=self.config['login'] or result.get('scopes')!=[SCOPE] or not isinstance(result.get('user_id'),str) or not re.fullmatch('[0-9]+',result['user_id']) or (pinned and result['user_id']!=pinned) or type(result.get('expires_in')) is not int or result['expires_in']<=0:raise WrongIdentity('Identity, application or scope refused')
  return result
 def accept(self,response,pinned=None):
  if not isinstance(response,dict) or any(not isinstance(response.get(k),str) or not 1<=len(response[k])<=4096 for k in ('access_token','refresh_token')):raise Refused('Invalid token response')
  atomic(self.root/'candidate.json',{'response':response,'pinned':pinned})
  try:identity=self.validate(response['access_token'],pinned)
  except WrongIdentity:
   try:self.remote('revoke',{'client_id':self.config['clientId'],'token':response['access_token']})
   except Refused:pass
   (self.root/'candidate.json').unlink(missing_ok=True)
   raise
  record={**{k:response[k] for k in ('access_token','refresh_token')},'user_id':identity['user_id'],'login':identity['login'],'client_id':identity['client_id'],'scopes':[SCOPE],'expires_at':self.clock()+identity['expires_in'],'validated_at':self.clock()}
  atomic(self.root/'tokens.json',record);(self.root/'candidate.json').unlink(missing_ok=True)
  return record
 def refresh(self):
  candidate=self.root/'candidate.json'
  if candidate.exists():
   row=read(candidate);return self.accept(row['response'],row.get('pinned'))
  row=read(self.root/'tokens.json')
  if row['expires_at']<self.clock()+600:
   return self.accept(self.remote('token',{'client_id':self.config['clientId'],'grant_type':'refresh_token','refresh_token':row['refresh_token']}),row['user_id'])
  identity=self.validate(row['access_token'],row['user_id']);row['validated_at']=self.clock();row['expires_at']=self.clock()+identity['expires_in'];atomic(self.root/'tokens.json',row);return row
 def authorize(self,display=print,sleep=time.sleep):
  if (self.root/'tokens.json').exists() or (self.root/'candidate.json').exists():raise Refused('Existing authorization preserved; use refresh')
  row=self.remote('device',{'client_id':self.config['clientId'],'scopes':SCOPE})
  if not isinstance(row,dict) or not re.fullmatch('[A-Za-z0-9-]{4,32}',str(row.get('user_code',''))) or not isinstance(row.get('device_code'),str) or not 8<=len(row['device_code'])<=1024 or type(row.get('expires_in')) is not int or not 1<=row['expires_in']<=3600 or type(row.get('interval')) is not int or not 1<=row['interval']<=60:raise Refused('Invalid device code response')
  display('Open https://www.twitch.tv/activate and enter this temporary code: '+row['user_code'])
  deadline=self.clock()+row['expires_in'];interval=max(5,row['interval'])
  while self.clock()<deadline:
   sleep(interval)
   if self.clock()>=deadline:break
   try:return self.accept(self.remote('token',{'client_id':self.config['clientId'],'scopes':SCOPE,'device_code':row['device_code'],'grant_type':'urn:ietf:params:oauth:grant-type:device_code'}))
   except Remote as error:
    if error.reason=='slow_down':interval=min(120,interval+5)
    elif error.reason!='authorization_pending':raise
  raise Refused('Device authorization expired')

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--directory',required=True);parser.add_argument('action',choices=['authorize','refresh','key']);parser.add_argument('--mode');args=parser.parse_args()
 root=Path(args.directory)
 if not root.is_dir() or any(p.is_symlink() for p in (root,*root.parents)):raise Refused('Create a dedicated private directory first')
 if os.name=='posix' and root.stat().st_mode&0o077:raise Refused('Directory must use 0700')
 if args.action=='key':
  if not args.mode or not re.fullmatch('[a-z][a-z0-9-]{0,39}',args.mode):raise Refused('Explicit mode required')
  path=root/'relay-keys.json';keys=read(path) if path.exists() else {}
  if args.mode in keys:raise Refused('Existing backend key preserved')
  if len(keys)>=64:raise Refused('Backend limit reached')
  keys[args.mode]=secrets.token_hex(32);atomic(path,keys);atomic(root/(args.mode+'.secret'),keys[args.mode]);print('Private key file created; no key printed.');return
 if os.name!='posix':raise Refused('OAuth CLI requires a POSIX private filesystem')
 import fcntl
 fd=os.open(root/'oauth.lock',os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,'w') as lock:
  try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  except BlockingIOError:raise Refused('Another OAuth operation is running')
  auth=Auth(root)
  if args.action=='authorize':auth.authorize()
  else:auth.refresh()
 print('Authorization verified; no credentials printed.')
if __name__=='__main__':
 try:main()
 except (Refused,OSError,ValueError,KeyError,TypeError):raise SystemExit('Operation refused. Check private configuration and Twitch authorization; credentials not logged.')
