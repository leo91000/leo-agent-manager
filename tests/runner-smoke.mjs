import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { setTimeout } from 'node:timers/promises'

async function main() {
  const image = process.argv[2] || 'leo-firecracker:dev'
  const docker = (...args) => execFileSync('docker', ['--context', 'default', ...args], { encoding: 'utf8', timeout: 180000, stdio: ['pipe', 'pipe', 'pipe'] }).trim()
  const root = await mkdtemp(path.join(process.env.VM_TEST_ROOT || os.tmpdir(), 'leo-microvm-'))
  const name = `leo-vm-test-${randomUUID().slice(0, 8)}`
  const headers = { authorization: 'Bearer fixture-runner-token' }
  let url
  let auth
  async function until(operation, timeout = 180000) {
    const deadline = Date.now() + timeout
    while (Date.now() < deadline) {
      const result = await operation()
      if (result)
        return result
      await setTimeout(100)
    }
    throw new Error('MicroVM test timed out')
  }
  async function api(endpoint, method = 'GET') {
    const response = await fetch(url + endpoint, { method, headers, signal: AbortSignal.timeout(180000) })
    assert.equal(response.ok, true, `${method} ${endpoint}: ${response.status}`)
    return response
  }
  try {
    for (const dir of ['data/runner-plans', 'state'])
      await mkdir(path.join(root, dir), { recursive: true })
    await writeFile(path.join(root, 'data/runner-secret'), 'fixture-runner-token')
    await writeFile(path.join(root, 'data/private-manager-canary'), 'must-not-enter-guest')
    const runId = randomUUID()
    const runRoot = `/data/runs/${runId}`
    const source = path.join(root, 'data/runs', runId)
    for (const dir of ['workspace', 'home/.codex', 'chat-input', 'output'])
      await mkdir(path.join(source, dir), { recursive: true })
    await writeFile(path.join(source, 'home/.codex/leo-managed-auth'), '1')
    await writeFile(path.join(source, 'chat-input/messages.json'), '[]')
    const fixture = `
import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import { execFileSync } from 'node:child_process';
const root=${JSON.stringify(runRoot)};
const mode=process.argv[2];
assert.match(execFileSync('uname',['-r'],{encoding:'utf8'}),/^6\\.12\\.109/);
for(const path of ['/data/private-manager-canary','/data/runner-secret','/var/run/docker.sock'])assert.equal(fs.existsSync(path),false,path);
assert.equal(process.env.RUNNER_TOKEN,undefined);
if(mode==='first') {
  const env=JSON.parse(execFileSync('/usr/local/bin/leo',['toolkit-env'],{encoding:'utf8'}));
  assert.equal(env.LEO_TOOLKIT_DIR,'/opt/leo-toolkit');
  for(const tool of ['cargo','rustc','pnpm','python','uv','rg','fd','gh','codex'])execFileSync(tool,['--version'],{env,timeout:30000});
  const auth=await new Promise((resolve,reject)=>{const socket=net.connect('/run/leo-auth.sock',()=>socket.write('{"refresh":false}\\n'));let data='';socket.on('data',chunk=>{data+=chunk;if(data.includes('\\n')){socket.end();resolve(JSON.parse(data))}});socket.on('error',reject);});
  assert.equal(auth.accessToken,'fixture-access-token');
  console.log('probe.ready');
  const deadline=Date.now()+20000;
  while(!fs.readFileSync('/run/leo-chat/messages.json','utf8').includes('steered')){assert.ok(Date.now()<deadline,'live inbox');await new Promise(r=>setTimeout(r,100));}
  console.log(execFileSync('docker',['run','--rm','busybox:1.37','echo','nested-docker-ok'],{encoding:'utf8',timeout:120000}));
  fs.writeFileSync(root+'/workspace/compose.yaml',JSON.stringify({services:{probe:{image:'busybox:1.37',command:['echo','compose-ok']}}}));
  assert.match(execFileSync('docker',['compose','-f',root+'/workspace/compose.yaml','run','--rm','probe'],{encoding:'utf8',timeout:30000}),/compose-ok/);
  execFileSync('docker',['compose','-f',root+'/workspace/compose.yaml','down'],{timeout:30000});
  fs.writeFileSync(root+'/workspace/preserved','uncommitted work');
} else {
  assert.equal(fs.readFileSync(root+'/workspace/preserved','utf8'),'uncommitted work');
  console.log(execFileSync('docker',['run','--rm','--pull=never','busybox:1.37','echo','cached-docker-ok'],{encoding:'utf8',timeout:30000}));
}
if(mode==='cancel'||mode==='crash') {
  fs.writeFileSync(root+'/workspace/interrupted','saved before interruption');
  execFileSync('sync');
  console.log('probe.pause');
  await new Promise(()=>{setInterval(()=>{},1000)});
}
if(mode==='recover')assert.equal(fs.readFileSync(root+'/workspace/interrupted','utf8'),'saved before interruption');
fs.writeFileSync(root+'/output/result.md','guest test passed '+mode);
console.log('probe.done');
`
    await writeFile(path.join(source, 'workspace/probe.mjs'), fixture)
    auth = createServer(socket => socket.once('data', () => socket.end('{"accessToken":"fixture-access-token"}\n')))
    await new Promise(resolve => auth.listen(path.join(source, 'home/.codex/leo-auth.sock'), resolve))
    docker('run', '-d', '--name', name, '--user', '0:0', '--read-only', '--cap-drop', 'ALL', ...['SYS_ADMIN', 'NET_ADMIN', 'SYS_CHROOT', 'SETUID', 'SETGID', 'MKNOD', 'CHOWN', 'FOWNER', 'KILL', 'DAC_OVERRIDE'].flatMap(cap => ['--cap-add', cap]), '--security-opt', 'apparmor=unconfined', '--security-opt', 'seccomp=unconfined', '--device', '/dev/kvm', '--device', '/dev/net/tun', '--sysctl', 'net.ipv4.ip_forward=1', '--sysctl', 'net.ipv6.conf.all.disable_ipv6=1', '--tmpfs', '/run', '--tmpfs', '/tmp', '-v', `${root}/data:/data`, '-v', `${root}/state:/runner-state`, '-p', '127.0.0.1::4311', '--memory', '6g', '--cpus', '3', '--entrypoint', '/usr/local/bin/leo', image, 'runner-broker')
    url = `http://${await until(() => {
      try {
        return docker('port', name, '4311/tcp')
      }
      catch {
        return false
      }
    }, 10000)}`
    await until(() => fetch(`${url}/health`).then(r => r.ok).catch(() => false))
    for (const mode of ['first', 'resume', 'cancel', 'crash', 'recover']) {
      const id = randomUUID()
      const plan = {
        id,
        runId,
        expires: Date.now() + 300000,
        sandbox: 'yolo',
        cwd: `${runRoot}/workspace`,
        command: ['/usr/local/bin/node', `${runRoot}/workspace/probe.mjs`, mode],
        chat: { output: `${runRoot}/output/result.md` },
        imports: [
          { source: `${runRoot}/workspace`, target: `${runRoot}/workspace`, readOnly: false },
          { source: `${runRoot}/home`, target: '/home/node', readOnly: false },
          { source: `${runRoot}/output`, target: `${runRoot}/output`, readOnly: false },
          { source: `${runRoot}/chat-input`, target: '/run/leo-chat', readOnly: true },
        ],
      }
      await writeFile(path.join(root, 'data/runner-plans', `${id}.json`), JSON.stringify(plan))
      const start = Date.now()
      await api(`/runs/${id}`, 'POST')
      const output = (async () => {
        const response = await api(`/runs/${id}/logs`)
        let pending = ''
        let text = ''
        try {
          for await (const chunk of response.body) {
            pending += Buffer.from(chunk).toString()
            while (pending.includes('\n')) {
              const end = pending.indexOf('\n')
              const event = JSON.parse(pending.slice(0, end))
              pending = pending.slice(end + 1)
              if (event.type !== 'output')
                continue
              const data = Buffer.from(event.data, 'base64').toString()
              text += data
              process.stdout.write(data)
              if (data.includes('probe.ready'))
                await writeFile(path.join(source, 'chat-input/messages.json'), '[{"text":"steered"}]')
              if (text.includes('probe.pause') && mode === 'cancel')
                await api(`/runs/${id}`, 'DELETE')
              if (text.includes('probe.pause') && mode === 'crash') {
                docker('kill', '--signal', 'KILL', name)
                docker('start', name)
                url = `http://${await until(() => {
                  try {
                    return docker('port', name, '4311/tcp')
                  }
                  catch {
                    return false
                  }
                })}`
                await until(() => fetch(`${url}/health`).then(r => r.ok).catch(() => false))
              }
            }
          }
        }
        catch (error) {
          if (mode !== 'crash' || !text.includes('probe.pause'))
            throw error
        }
        return text
      })()
      const waiting = async () => (await api(`/runs/${id}/wait`, 'POST')).json()
      let status = await waiting().catch((error) => {
        if (mode !== 'crash')
          throw error
        return { StatusCode: 143 }
      })
      const text = await output
      if (mode === 'crash')
        status = await waiting()
      assert.equal(status.StatusCode, ['cancel', 'crash'].includes(mode) ? 143 : 0, text)
      if (['cancel', 'crash'].includes(mode))
        assert.ok(text.includes('probe.pause'))
      else assert.equal(await readFile(path.join(source, 'output/result.md'), 'utf8'), `guest test passed ${mode}`)
      assert.equal(await readFile(path.join(source, 'workspace/preserved'), 'utf8').catch(() => null), null, 'guest edits must not affect host checkout')
      await api(`/runs/${id}`, 'DELETE')
      process.stdout.write(`${JSON.stringify({ mode, durationMs: Date.now() - start, status: 'passed' })}\n`)
    }
    // Independent disks must run concurrently and enforce each guest policy.
    const probes = []
    for (const sandbox of ['read-only', 'workspace-write']) {
      const runId = randomUUID()
      const id = randomUUID()
      const workspace = `/data/runs/${runId}/workspace`
      const directory = path.join(root, 'data/runs', runId, 'workspace')
      await mkdir(directory, { recursive: true })
      await writeFile(path.join(directory, 'sentinel'), sandbox)
      const code = `
        const fs=require('node:fs'), assert=require('node:assert/strict');
        const {spawnSync}=require('node:child_process');
        assert.equal(process.getuid(),1000);
        assert.notEqual(spawnSync('sudo',['-n','true']).status,0);
        assert.equal(fs.readFileSync(${JSON.stringify(`${workspace}/sentinel`)},'utf8'),${JSON.stringify(sandbox)});
        const write=()=>fs.writeFileSync(${JSON.stringify(`${workspace}/created`)},'guest only');
        ${sandbox === 'read-only' ? 'assert.throws(write,e=>e.code===\'EROFS\');' : 'write();'}
        console.log('parallel.ready');
        setTimeout(()=>console.log('parallel.done'),4000);
      `
      const plan = { id, runId, expires: Date.now() + 60000, sandbox, cwd: workspace, command: ['/usr/local/bin/node', '-e', code], imports: [{ source: workspace, target: workspace, readOnly: sandbox === 'read-only' }] }
      await writeFile(path.join(root, 'data/runner-plans', `${id}.json`), JSON.stringify(plan))
      await api(`/runs/${id}`, 'POST')
      probes.push({ id, sandbox, directory })
    }
    const outcomes = await Promise.allSettled(probes.map(async ({ id, sandbox, directory }) => {
      const response = await api(`/runs/${id}/logs`)
      const records = (await response.text()).trim().split('\n').map(line => JSON.parse(line))
      const output = records.filter(row => row.type === 'output').map(row => Buffer.from(row.data, 'base64').toString()).join('')
      const status = await (await api(`/runs/${id}/wait`, 'POST')).json()
      assert.equal(status.StatusCode, 0, output)
      assert.match(output, /parallel.ready[\s\S]*parallel.done/)
      assert.equal(await readFile(path.join(directory, 'created'), 'utf8').catch(() => null), null)
      process.stdout.write(`${JSON.stringify({ mode: sandbox, status: 'passed' })}\n`)
    }))
    for (const outcome of outcomes) {
      if (outcome.status === 'rejected')
        throw outcome.reason
    }
  }
  catch (error) {
    console.error(error)
    try {
      console.error(docker('logs', name))
      console.error(docker('exec', name, 'sh', '-c', 'tail -60 /runner-state/*.boot.log'))
    }
    catch {
    }
    process.exitCode = 1
  }
  finally {
    if (process.env.KEEP_VM_TEST !== '1') {
      try {
        docker('rm', '-f', name)
      }
      catch {
      }
    }
    if (auth)
      await new Promise(resolve => auth.close(resolve))
    if (process.env.KEEP_VM_TEST !== '1') {
      docker('run', '--rm', '--user', '0:0', '-v', `${root}:/cleanup`, '--entrypoint', '/bin/rm', image, '-rf', '/cleanup/data', '/cleanup/state')
      await rm(root, { recursive: true, force: true })
    }
    else {
      process.stdout.write(`Evidence retained at ${root}\n`)
    }
  }
}
main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
