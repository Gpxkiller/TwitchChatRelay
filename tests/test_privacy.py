from pathlib import Path
import re,unittest
class PrivacyTests(unittest.TestCase):
 def test_only_generic_sources_and_examples(self):
  root=Path(__file__).parents[1]
  deny=re.compile(r'-----BEGIN [A-Z ]*PRIVATE KEY|gh[pousr]_[A-Za-z0-9]{20,}|C:[/\\]Users[/\\]',re.I)
  for p in root.rglob('*'):
   if not p.is_file() or any(x in {'.git','target','dist','__pycache__'} for x in p.relative_to(root).parts):continue
   if p==Path(__file__):continue
   self.assertNotIn(p.suffix.lower(),{'.sqlite','.mca','.dat','.png','.log','.secret'},str(p))
   self.assertIsNone(deny.search(p.read_text(encoding='utf8')),str(p.relative_to(root)))
