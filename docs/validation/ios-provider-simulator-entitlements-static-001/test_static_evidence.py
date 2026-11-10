import importlib.util,pathlib,struct,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('signature_parser',ROOT/'inspect-binary.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
DER=bytes.fromhex('700f020101b00a30080c016b30030c0176')

def image(section_name=b'__ents_der',offset=184):
 header=struct.pack('<IiiIIIII',0xfeedfacf,0x100000c,0,2,1,152,0,0)
 segment=struct.pack('<II16sQQQQiiII',0x19,152,b'__TEXT',0x1000,3,184,3,5,5,1,0)
 section=struct.pack('<16s16sQQIIIIIIII',section_name,b'__TEXT',0x1000,3,offset,0,0,0,0,0,0,0)
 return header+segment+section+b'abc'

class LiteralParserTests(unittest.TestCase):
 def test_der_literal_dictionary(self):self.assertEqual(parser.der_dictionary(DER),{'k':['v']})
 def test_der_duplicate_key(self):
  with self.assertRaises(AssertionError):parser.der_dictionary(bytes.fromhex('7019020101b01430080c016b30030c017630080c016b30030c0176'))
 def test_der_trailing_byte(self):
  with self.assertRaises(AssertionError):parser.der_dictionary(DER+b'\0')
 def test_der_indefinite_length(self):
  with self.assertRaises(AssertionError):parser.tlv(bytes.fromhex('70800201010000'))
 def test_der_nonminimal_integer(self):
  with self.assertRaises(AssertionError):parser.tlv(bytes.fromhex('02020001'))
 def test_der_truncated_value(self):
  with self.assertRaises(AssertionError):parser.der_dictionary(DER[:-1])
 def test_der_version_not_admitted(self):
  b=bytearray(DER);b[4]=2
  with self.assertRaises(AssertionError):parser.der_dictionary(bytes(b))
 def test_real_section_name_present(self):
  x=parser.sections(image());self.assertEqual([(s['segment'],s['section'],s['bytes'],s['fileOffset'])for s in x],[('__TEXT','__ents_der',3,184)])
 def test_valid_absence(self):
  x=parser.sections(image(b'__text'));self.assertFalse(any(s['section']=='__ents_der'for s in x))
 def test_section_file_range_rejected(self):
  with self.assertRaises(AssertionError):parser.sections(image(offset=190))
 def test_segment_section_identity_rejected(self):
  b=bytearray(image());b[32+72+16:32+72+32]=b'__DATA'+b'\0'*10
  with self.assertRaises(AssertionError):parser.sections(bytes(b))
 def test_explicit_buffer_never_rereads_path(self):
  data=struct.pack('<IiiIIIII',0xfeedfacf,0x100000c,0,2,1,16,0,0)+struct.pack('<IIII',0x1d,16,48,12)+struct.pack('>III',0xfade0cc0,12,0)
  class PathThatMustNotBeRead:
   def read_bytes(self):raise AssertionError('Unexpected second read')
   def __str__(self):return 'literal-immutable-buffer'
  with tempfile.TemporaryDirectory()as d:
   parser.OUT=pathlib.Path(d);result=parser.parse(PathThatMustNotBeRead(),'literal',data)
  self.assertEqual(result['sha256'],parser.sha(data))
  self.assertEqual(result['machHeader']['flags'],'0x0')
 def test_code_signature_out_of_range(self):
  data=struct.pack('<IiiIIIII',0xfeedfacf,0x100000c,0,2,1,16,0,0)+struct.pack('<IIII',0x1d,16,1000,100)
  with tempfile.TemporaryDirectory()as d:
   p=pathlib.Path(d)/'literal';p.write_bytes(data);parser.OUT=pathlib.Path(d)
   with self.assertRaises(AssertionError):parser.parse(p,'bad-range')

if __name__=='__main__':unittest.main()
