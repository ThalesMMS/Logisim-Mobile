import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { debuglog } from 'node:util';

export const GET_STATUS = '/android.emulation.control.EmulatorController/getStatus';
export const PROTO_SHA256 = '1d62c6bcad5f06621f90ec2bf26c661ba769ccd0f1416b5314d25a68e04eee5f';
const ADB = '/Users/thales/Library/Android/sdk/platform-tools/adb';
const PROTO = '/Users/thales/Library/Android/sdk/emulator/lib/emulator_controller.proto';
const PID = 28678;
const SERIAL = 'emulator-5556';
const DESCRIPTOR_SHA256 = 'c252221fa860ad424bfcb98228ddd9d423f9e4a6ec98e5397a0a8c1d64dc1489';

export function discoverEndpoint(run = execFileSync, read = readFileSync) {
  try {
    const options = { encoding: 'utf8', timeout: 5000, stdio: ['ignore', 'pipe', 'pipe'] };
    const binary = run('/bin/ps', ['-p', String(PID), '-o', 'comm='], options).trim();
    if (!binary.endsWith('/qemu-system-aarch64-headless')) return { status: 'PID_MISMATCH' };
    const avd = run(ADB, ['-s', SERIAL, 'emu', 'avd', 'name'], options);
    if (avd.replace(/\r/g, '').trim() !== 'ISIS-VALIDATE\nOK') return { status: 'AVD_MISMATCH' };
    const portReply = run(ADB, ['-s', SERIAL, 'emu', 'avd', 'grpc'], options);
    if (/^KO: No active gRPC service\r?\n?$/.test(portReply)) {
      return { status: 'NO_ACTIVE_GRPC_SERVICE', pid: PID, serial: SERIAL, credentialRead: false, rpcSent: false };
    }
    const matched = portReply.replace(/\r/g, '').trim().match(/^(\d{1,5})\nOK$/);
    if (!matched) return { status: 'UNRECOGNIZED_PORT_REPLY' };
    const port = Number(matched[1]);
    if (port < 1 || port > 65535) return { status: 'INVALID_PORT' };
    const listeners = run('/usr/sbin/lsof', ['-nP', '-a', '-p', String(PID), '-iTCP', '-sTCP:LISTEN', '-Fpcn'], options);
    if (!listeners.includes(`p${PID}\n`) || !listeners.split('\n').some(line => line === `n127.0.0.1:${port}`)) {
      return { status: 'PORT_NOT_BOUND_TO_PID' };
    }
    if (createHash('sha256').update(read(PROTO)).digest('hex') !== PROTO_SHA256) return { status: 'PROTO_CHANGED' };
    return { status: 'ATTRIBUTED', pid: PID, serial: SERIAL, host: '127.0.0.1', port,
      protoSha256: PROTO_SHA256, verifiedAt: Date.now(), credentialRead: false, rpcSent: false };
  } catch {
    return { status: 'DISCOVERY_GETTER_FAILED', credentialRead: false, rpcSent: false };
  }
}

export function assertProof(proof, now = Date.now()) {
  if (proof?.status !== 'ATTRIBUTED' || proof.pid !== PID || proof.serial !== SERIAL
      || proof.host !== '127.0.0.1' || !Number.isInteger(proof.port) || proof.port < 1 || proof.port > 65535
      || proof.protoSha256 !== PROTO_SHA256 || !Number.isFinite(proof.verifiedAt)
      || now < proof.verifiedAt || now - proof.verifiedAt > 30000) throw new Error('ENDPOINT_NOT_ATTRIBUTED');
}

export function unaryPayload(frame) {
  if (!Buffer.isBuffer(frame) || frame.length < 5 || frame.length > 1048581
      || frame[0] !== 0 || frame.readUInt32BE(1) !== frame.length - 5) throw new Error('INVALID_UNARY_FRAME');
  return frame.subarray(5);
}

export function decodeStatus(payload) {
  const descriptor = fileURLToPath(new URL('./controller.pb', import.meta.url));
  if (createHash('sha256').update(readFileSync(descriptor)).digest('hex') !== DESCRIPTOR_SHA256) {
    throw new Error('DESCRIPTOR_CHANGED');
  }
  const decoded = spawnSync('/opt/homebrew/bin/protoc', [
    '--decode=android.emulation.control.EmulatorStatus', `--descriptor_set_in=${descriptor}`,
  ], { input: payload, encoding: 'utf8', timeout: 5000, maxBuffer: 2097152, stdio: ['pipe', 'pipe', 'pipe'] });
  if (decoded.status !== 0 || decoded.error) throw new Error('STATUS_DECODE_FAILED');
  const version = decoded.stdout.match(/^version: "(\d{1,3}\.\d{1,3}\.\d{1,5}(?:\.\d{1,5})?)"$/m)?.[1];
  if (!version) throw new Error('STATUS_VERSION_UNRECOGNIZED');
  return { version, booted: /^booted: true$/m.test(decoded.stdout),
    uptimeMilliseconds: decoded.stdout.match(/^uptime: (\d{1,20})$/m)?.[1] ?? '0' };
}

async function http2Getter(proof, authorization) {
  if (debuglog('http2').enabled) throw new Error('HTTP2_DEBUG_ENABLED');
  const { connect } = await import('node:http2');
  return new Promise((resolve, reject) => {
    const connection = connect(`http://${proof.host}:${proof.port}`);
    let request, timer, finished = false, count = 0;
    const parts = [];
    let httpStatus, grpcStatus, contentType;
    const finish = (error) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      request?.destroy();
      connection.destroy();
      if (error) reject(new Error('GETTER_TRANSPORT_FAILED'));
      else resolve(Buffer.concat(parts));
    };
    timer = setTimeout(() => finish(true), 5000);
    connection.on('error', () => finish(true));
    request = connection.request({ ':method': 'POST', ':path': GET_STATUS,
      'content-type': 'application/grpc', te: 'trailers', 'grpc-timeout': '5S', authorization });
    request.on('error', () => finish(true));
    request.on('response', headers => {
      httpStatus = headers[':status'];
      contentType = headers['content-type'];
      grpcStatus = headers['grpc-status'];
    });
    request.on('trailers', headers => { grpcStatus = headers['grpc-status']; });
    request.on('data', part => {
      count += part.length;
      if (count > 1048581) finish(true);
      else parts.push(part);
    });
    request.on('end', () => finish(httpStatus !== 200 || !/^application\/grpc(?:\+proto)?(?:;.*)?$/.test(contentType ?? '')
      || grpcStatus !== '0'));
    request.end(Buffer.alloc(5));
  });
}

export async function getStatus(proof, loadCredential, { transport = http2Getter, decode = decodeStatus } = {}) {
  if (debuglog('http2').enabled) {
    return { status: 'NOT_RUN_HTTP2_DEBUG_ENABLED', credentialRead: false, rpcSent: false };
  }
  try { assertProof(proof); }
  catch { return { status: 'NOT_RUN_ENDPOINT_NOT_ATTRIBUTED', credentialRead: false, rpcSent: false }; }
  let credential;
  let rpcAttempted = false;
  try {
    // No default reader exists. Root must approve the exact existing credential source first.
    credential = await loadCredential();
    if (credential?.type !== 'existing-bearer' || typeof credential.sourcePath !== 'string'
        || !credential.sourcePath.startsWith('/') || /[\r\n]/.test(credential.sourcePath)
        || typeof credential.value !== 'string' || !/^[\x21-\x7e]+$/.test(credential.value)) {
      throw new Error('INVALID_CREDENTIAL');
    }
    assertProof(proof);
    rpcAttempted = true;
    const frame = await transport(proof, `Bearer ${credential.value}`);
    const status = decode(unaryPayload(frame));
    return { status: 'GETTER_PASS', pid: proof.pid, serial: proof.serial, host: proof.host, port: proof.port,
      credential: { sourcePath: credential.sourcePath, type: 'existing-bearer', exists: true },
      requestHeaders: { authorization: 'Bearer <redacted>', 'content-type': 'application/grpc', te: 'trailers' },
      method: GET_STATUS, emulator: status };
  } catch {
    // Never return upstream errors, headers, response text, credential hashes or JWT claims.
    return { status: 'GETTER_FAILED', credentialLoadAttempted: true, rpcAttempted, retry: false };
  } finally {
    credential = undefined;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const discoveryOnly = process.argv.length === 3 && process.argv[2] === '--discover-only';
  console.log(JSON.stringify(discoveryOnly ? discoverEndpoint() : { status: 'PREPARED_ONLY_NO_RPC' }));
}
