import importlib.util, os, tempfile, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('auth',Path(__file__).parents[1]/'tools/auth.py');a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
class OAuthTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.now=2000000;self.calls=[]
  self.config={'enabled':False,'clientId':'exampleclient','login':'exampleviewer'};a.atomic(self.root/'bridge.json',self.config)
  self.identity={'client_id':'exampleclient','login':'exampleviewer','scopes':[a.SCOPE],'user_id':'123','expires_in':3600}
  self.response={'access_token':'fixture-access','refresh_token':'fixture-refresh'}
  self.auth=a.Auth(self.root,self.remote,lambda:self.now)
 def tearDown(self):self.tmp.cleanup()
 def remote(self,endpoint,fields=None,token=None):
  self.calls.append((endpoint,fields,token))
  return self.identity if endpoint=='validate' else self.response
 def test_accept_stores_pinned_identity_and_exact_scope(self):
  row=self.auth.accept(self.response);self.assertEqual(row['user_id'],'123');self.assertEqual(row['scopes'],[a.SCOPE]);self.assertFalse((self.root/'candidate.json').exists())
 def test_other_account_rejected_and_revoked(self):
  self.identity['login']='other';self.assertRaises(a.WrongIdentity,self.auth.accept,self.response);self.assertFalse((self.root/'tokens.json').exists());self.assertEqual(self.calls[-1][0],'revoke')
 def test_other_app_rejected(self):self.identity['client_id']='different';self.assertRaises(a.WrongIdentity,self.auth.accept,self.response)
 def test_extra_scope_rejected(self):self.identity['scopes'].append('chat:edit');self.assertRaises(a.WrongIdentity,self.auth.accept,self.response)
 def test_expired_token_rejected(self):self.identity['expires_in']=0;self.assertRaises(a.WrongIdentity,self.auth.accept,self.response)
 def test_user_id_type_rejected(self):self.identity['user_id']=123;self.assertRaises(a.WrongIdentity,self.auth.accept,self.response)
 def test_refresh_cannot_switch_identity(self):self.assertRaises(a.WrongIdentity,self.auth.accept,self.response,'999')
 def test_missing_refresh_token_rejected(self):self.assertRaises(a.Refused,self.auth.accept,{'access_token':'fixture'})
 def test_rotation_is_saved_before_network_and_recovers(self):
  def outage(*args,**kwargs):self.assertTrue((self.root/'candidate.json').exists());raise a.Remote('unavailable')
  self.auth.remote=outage;self.assertRaises(a.Remote,self.auth.accept,self.response,'123')
  self.auth.remote=self.remote;self.auth.refresh();self.assertEqual([c[0] for c in self.calls],['validate']);self.assertFalse((self.root/'candidate.json').exists())
 def test_valid_token_is_validated_without_refresh(self):self.auth.accept(self.response);self.calls.clear();self.auth.refresh();self.assertEqual([c[0] for c in self.calls],['validate'])
 def test_near_expiry_uses_refresh_once(self):self.auth.accept(self.response);self.now+=3100;self.calls.clear();self.auth.refresh();self.assertEqual([c[0] for c in self.calls],['token','validate'])
 def test_pairing_refuses_existing_authorization(self):self.auth.accept(self.response);self.assertRaises(a.Refused,self.auth.authorize)
 def test_device_url_is_not_trusted(self):
  self.auth.remote=lambda *args,**kwargs:{'user_code':'ABCD','device_code':'fixture-device','expires_in':60,'interval':1,'verification_uri':'https://untrusted.example'} if args[0]=='device' else self.remote(*args,**kwargs)
  shown=[]
  def sleep(seconds):self.now+=seconds
  self.auth.authorize(shown.append,sleep);self.assertIn('https://www.twitch.tv/activate',shown[0]);self.assertNotIn('untrusted',shown[0]);self.assertNotIn('fixture-device',shown[0])
  token_calls=[c for c in self.calls if c[0]=='token'];self.assertEqual(token_calls[0][1]['scopes'],a.SCOPE)
 def test_slow_down_is_observed(self):
  count=0;waits=[]
  def remote(endpoint,fields=None,token=None):
   nonlocal count
   if endpoint=='device':return {'user_code':'ABCD','device_code':'fixture-device','expires_in':100,'interval':1}
   if endpoint=='token':
    count+=1
    if count==1:raise a.Remote('slow_down')
   return self.remote(endpoint,fields,token)
  self.auth.remote=remote
  def sleep(seconds):waits.append(seconds);self.now+=seconds
  self.auth.authorize(lambda _:None,sleep);self.assertEqual(waits,[5,10])
 def test_oversized_private_file_refused(self):p=self.root/'huge';p.write_bytes(b'x'*65537);self.assertRaises(a.Refused,a.read,p)
 @unittest.skipUnless(os.name=='posix','POSIX permissions')
 def test_private_modes_ignore_umask(self):
  self.auth.accept(self.response);self.assertEqual((self.root/'tokens.json').stat().st_mode&0o777,0o600)
  os.chmod(self.root/'tokens.json',0o644);self.assertRaises(a.Refused,a.read,self.root/'tokens.json')
