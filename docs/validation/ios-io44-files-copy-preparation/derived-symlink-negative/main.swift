
import Foundation
import CryptoKit
let base=URL(fileURLWithPath:CommandLine.arguments[1]).resolvingSymlinksInPath()
let dir=base.appendingPathComponent("receiver/Documents/LogisimE2E/aabbccddeeff",isDirectory:true)
try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
let bytes=try Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[2]))
let fixture=dir.appendingPathComponent("io-showcase-source.circ")
try bytes.write(to:fixture)
let hash=SHA256.hash(data:bytes).map{String(format:"%02x",$0)}.joined()
let source=try FilesCopySource(configuration:["route":"files-copy","peerDisplayName":"Clipboard Receiver","runID":"aabbccddeeff","documents":dir.path,"fixture":fixture.path,"fixtureSHA256":hash])
let external=base.appendingPathComponent("outside.circ")
try bytes.write(to:external)
try FileManager.default.removeItem(at:fixture)
try FileManager.default.createSymbolicLink(at:fixture,withDestinationURL:external)
do {
 let out=try source.stage(bytes,filename:"io-showcase-55AE3E74-59E7-4A4B-ACFD-B1C4AB7EACA4.circ")
 try source.verify(out)
 print("ACCEPTED fixture symlink introduced after init; selected bytes verified")
} catch { print("REJECTED",error) }
