import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import { discoverEndpoint, getStatus, unaryPayload, assertProof, decodeStatus, PROTO_SHA256, GET_STATUS } from './getter-status.mjs';

const proof = () => ({ status: 'ATTRIBUTED', pid: 28678, serial: 'emulator-5556', host: '127.0.0.1',
  port: 60097, protoSha256: PROTO_SHA256, verifiedAt: Date.now() });
const frame = () => Buffer.from([0, 0, 0, 0, 1, 8]);
const synthetic = 'synthetic-private-marker-for-offline-test';
const credential = () => ({ type: 'existing-bearer', sourcePath: '/approved/synthetic/source', value: synthetic });

test('KO reply is not success despite a successful console process', () => {
  let reads = 0;
  const run = (binary, args) => {
    if (binary === '/bin/ps') return '/sdk/qemu-system-aarch64-headless\n';
    if (args.at(-1) === 'name') return 'ISIS-VALIDATE\r\nOK\r\n';
    if (args.at(-1) === 'grpc') return 'KO: No active gRPC service\r\n';
    throw new Error('Unexpected getter');
  };
  assert.deepEqual(discoverEndpoint(run, () => { reads++; }), {
    status: 'NO_ACTIVE_GRPC_SERVICE', pid: 28678, serial: 'emulator-5556', credentialRead: false, rpcSent: false,
  });
  assert.equal(reads, 0);
});

test('an unbound port is rejected before proto or credentials', () => {
  const run = (binary, args) => {
    if (binary === '/bin/ps') return '/sdk/qemu-system-aarch64-headless\n';
    if (args.at(-1) === 'name') return 'ISIS-VALIDATE\nOK\n';
    if (args.at(-1) === 'grpc') return '60097\nOK\n';
    return 'p28678\ncqemu-system-aarch64-headless\nn127.0.0.1:60089\n';
  };
  assert.equal(discoverEndpoint(run, () => { throw new Error('must not read'); }).status, 'PORT_NOT_BOUND_TO_PID');
});

test('missing endpoint never invokes credential reader or transport', async () => {
  let called = 0;
  const result = await getStatus({ status: 'NO_ACTIVE_GRPC_SERVICE' }, () => { called++; }, {
    transport: () => { called++; },
  });
  assert.deepEqual(result, { status: 'NOT_RUN_ENDPOINT_NOT_ATTRIBUTED', credentialRead: false, rpcSent: false });
  assert.equal(called, 0);
});

test('nonloopback, stale and mismatched source proofs are rejected', () => {
  for (const changed of [{ host: 'example.com' }, { pid: 45345 }, { serial: 'emulator-5558' },
    { verifiedAt: Date.now() - 31000 }, { port: 0 }, { protoSha256: 'changed' }]) {
    assert.throws(() => assertProof({ ...proof(), ...changed }), /ENDPOINT_NOT_ATTRIBUTED/);
  }
});

test('only a single uncompressed complete unary frame is accepted', () => {
  assert.deepEqual(unaryPayload(frame()), Buffer.from([8]));
  for (const invalid of [Buffer.alloc(0), Buffer.from([1, 0, 0, 0, 0]), Buffer.from([0, 0, 0, 0, 2, 8]),
    Buffer.concat([frame(), frame()])]) assert.throws(() => unaryPayload(invalid), /INVALID_UNARY_FRAME/);
});

test('the frozen installed proto decodes a literal status response', () => {
  const payload = Buffer.from('0a0933372e312e31312e30102a1801', 'hex');
  assert.deepEqual(decodeStatus(payload), { version: '37.1.11.0', booted: true, uptimeMilliseconds: '42' });
});

test('successful getter sends authorization only to transport and returns redaction', async () => {
  let transported = 0;
  const result = await getStatus(proof(), credential, {
    transport: (endpoint, authorization) => {
      transported++;
      assert.equal(endpoint.host, '127.0.0.1');
      assert.equal(authorization, `Bearer ${synthetic}`);
      return frame();
    },
    decode: payload => { assert.deepEqual(payload, Buffer.from([8])); return { version: '37.1.11.0', booted: true }; },
  });
  assert.equal(transported, 1);
  assert.equal(result.status, 'GETTER_PASS');
  assert.equal(result.method, GET_STATUS);
  assert.equal(result.requestHeaders.authorization, 'Bearer <redacted>');
  assert.equal(JSON.stringify(result).includes(synthetic), false);
});

test('upstream errors and reflected secrets never enter failure results', async () => {
  const result = await getStatus(proof(), credential, {
    transport: () => { throw new Error(`authorization ${synthetic}`); },
  });
  assert.deepEqual(result, { status: 'GETTER_FAILED', credentialLoadAttempted: true, rpcAttempted: true, retry: false });
  assert.equal(JSON.stringify(result).includes(synthetic), false);
});

test('invalid credentials are rejected before any network attempt', async () => {
  let calls = 0;
  const result = await getStatus(proof(), () => ({ ...credential(), value: 'bad\nheader' }), {
    transport: () => { calls++; },
  });
  assert.equal(calls, 0);
  assert.equal(result.rpcAttempted, false);
});

test('credential-load failures are sanitized and never become network requests', async () => {
  const result = await getStatus(proof(), () => { throw new Error(synthetic); });
  assert.deepEqual(result, { status: 'GETTER_FAILED', credentialLoadAttempted: true, rpcAttempted: false, retry: false });
  assert.equal(JSON.stringify(result).includes(synthetic), false);
});

test('startup HTTP2 debug blocks the credential loader without a connection', () => {
  const module = new URL('./getter-status.mjs', import.meta.url).href;
  const source = `import {getStatus, PROTO_SHA256} from ${JSON.stringify(module)};
    let calls = 0;
    const result = await getStatus({status:'ATTRIBUTED', pid:28678, serial:'emulator-5556',
      host:'127.0.0.1', port:60097, protoSha256:PROTO_SHA256, verifiedAt:Date.now()},
      () => { calls++; throw new Error('must not read'); });
    console.log(JSON.stringify({result,calls}));`;
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', source], {
    env: { NODE_DEBUG: 'http2' }, encoding: 'utf8', timeout: 5000,
  });
  assert.equal(child.status, 0);
  assert.equal(child.stderr, '');
  assert.deepEqual(JSON.parse(child.stdout), {
    result: { status: 'NOT_RUN_HTTP2_DEBUG_ENABLED', credentialRead: false, rpcSent: false }, calls: 0,
  });
});
