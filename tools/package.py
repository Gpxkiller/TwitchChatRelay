from pathlib import Path
import zipfile
root=Path(__file__).parents[1]
selected=['tools/auth.py','bridge.example.json','deploy/twitch-chat-relay.service','deploy/twitch-chat-refresh.service','deploy/twitch-chat-refresh.timer','README.md','SECURITY.md','VALIDATION.md','CHANGELOG.md','LICENSE']
with zipfile.ZipFile(root/'dist/TwitchChatRelay-tools.zip','w',zipfile.ZIP_DEFLATED) as archive:
 for name in selected:
  source=root/name
  if source.is_symlink():raise ValueError('Linked source refused')
  archive.write(source,name)
