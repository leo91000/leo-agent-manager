import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { chmod, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { setTimeout } from 'node:timers/promises'

const image = process.argv[2] || 'leo-agent-manager:test'
const docker = (...args) => execFileSync('docker', ['--context', 'default', ...args], { encoding: 'utf8', timeout: 120000, stdio: ['pipe', 'pipe', 'pipe'] }).trim()
async function main() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'leo-runner-smoke-'))
  const suffix = randomUUID().slice(0, 8)
  const manager = `leo-runner-manager-${suffix}`
  const broker = `leo-runner-broker-${suffix}`
  const headers = { authorization: 'Bearer runner-smoke-secret' }
  const runs = []
  let url = ''
  try {
    for (const directory of ['data/runner-plans', 'home/.codex', 'project/.agents/skills/hidden', 'other-project'])
      await mkdir(path.join(root, directory), { recursive: true })
    await writeFile(path.join(root, 'data/runner-secret'), 'runner-smoke-secret')
    await writeFile(path.join(root, 'data/private'), 'private-manager-canary')
    await writeFile(path.join(root, 'home/.codex/auth.json'), '{}')
    await writeFile(path.join(root, 'home/.codex/config.toml'), '[mcp_servers.private]\ncommand="private"')
    await writeFile(path.join(root, 'project/allowed'), 'allowed')
    await writeFile(path.join(root, 'project/.agents/skills/hidden/SKILL.md'), 'unselected')
    await writeFile(path.join(root, 'other-project/denied'), 'denied')
    const fixture = path.join(root, 'codex-fixture')
    await writeFile(fixture, `#!/usr/bin/env node
const fs = require('node:fs');
const assert = require('node:assert/strict');
let prompt = '';
process.stdin.on('data', chunk => prompt += chunk);
process.stdin.on('end', () => {
  const plan = JSON.parse(fs.readFileSync('/run/leo-plan.json', 'utf8'));
  const root = ${JSON.stringify(root)};
  assert.equal(fs.readFileSync(plan.cwd + '/allowed', 'utf8'), 'allowed');
  for (const file of [root + '/data/private', root + '/data/runner-secret', root + '/home/.codex/config.toml', root + '/other-project/denied', '/var/run/docker.sock', plan.cwd + '/.agents/skills/hidden/SKILL.md'])
    assert.throws(() => fs.readFileSync(file));
  assert.ok(!process.env.RUNNER_TOKEN);
  assert.ok(!process.env.GITHUB_TOKEN);
  assert.ok(!fs.readFileSync('/home/node/.codex/config.toml', 'utf8').includes('private'));
  if (plan.sandbox === 'read-only') {
    assert.throws(() => fs.writeFileSync(plan.cwd + '/forbidden', 'bad'));
    assert.ok(process.argv.includes('--sandbox'));
  } else {
    fs.writeFileSync(plan.cwd + '/written', 'ok');
    assert.ok(process.argv.includes('--dangerously-bypass-approvals-and-sandbox'));
  }
  console.log(JSON.stringify({type:'item.completed',item:{type:'agent_message',text:'Isolation assertions passed'}}));
  if (prompt.includes('hang')) { setInterval(() => {}, 1000); return; }
  fs.writeFileSync(process.argv[process.argv.indexOf('--output-last-message') + 1], 'Isolated fixture passed');
});
`)
    await chmod(fixture, 0o755)
    const security = JSON.parse(docker('info', '--format', '{{json .SecurityOptions}}'))
    const apparmor = security.some(value => value.includes('apparmor'))
    if (apparmor)
      execFileSync('sudo', ['apparmor_parser', '-r', path.resolve('deploy/leo-runner.apparmor')], { stdio: 'pipe' })
    docker('run', '-d', '--name', manager, '-v', `${root}:${root}`, '--entrypoint', 'node', image, '-e', 'setInterval(()=>{},1000)')
    docker('run', '-d', '--name', broker, '--user', '0:0', '-v', `${root}:${root}:ro`, '-v', '/var/run/docker.sock:/var/run/docker.sock', '-p', '127.0.0.1::4311', '-e', `DATA_DIR=${root}/data`, '-e', `RUNNER_MANAGER_CONTAINER=${manager}`, '-e', `RUNNER_APPARMOR_PROFILE=${apparmor ? 'leo-agent-sandbox' : ''}`, '--entrypoint', 'node', image, '--import', 'tsx', '/app/server/runner-broker.ts')
    url = `http://${docker('port', broker, '4311/tcp')}`
    for (let attempt = 0; attempt < 50; attempt++) {
      if (await fetch(`${url}/health`).then(response => response.ok).catch(() => false))
        break
      await setTimeout(200)
    }
    assert.equal((await fetch(`${url}/runs/${randomUUID()}`, { method: 'POST' })).status, 401)
    const prepare = `
      import { prepareExecution } from '/app/server/execution.ts';
      import { codexArgs } from '/app/server/worker.ts';
      import { agentInput, taskInput } from '/app/shared/contracts.ts';
      import { writeFile } from 'node:fs/promises';
      const {root,id,mode,probe,hang}=JSON.parse(process.argv.at(-1));
      const project={id:'11111111-1111-4111-8111-111111111111',name:'Allowed',path:root+'/project',baseBranch:'main'};
      const agent={...agentInput.parse({name:'Restricted',access:{projects:[project.id],skills:[],github:false,sandbox:mode}}),id:'22222222-2222-4222-8222-222222222222'};
      const task=taskInput.parse({name:'Test',prompt:hang?'hang':'test',agentId:agent.id,worktree:false});
      const run={id,snapshot:{agent,task,project,projects:[project],skills:[]}};
      const prepared=await prepareExecution(run,{dataDir:root+'/data',home:root+'/home',workspaceRoots:[root],runnerUrl:'http://runner'});
      run.workspace=prepared.cwd; run.workspaces=prepared.workspaces;
      const args=probe ? ['sandbox','-c','sandbox_mode="workspace-write"','--','node','-e', 'const fs=require("fs"),a=require("assert/strict");fs.writeFileSync("sandbox-allowed","ok");a.throws(()=>fs.writeFileSync("/home/node/sandbox-denied","bad"));console.log("Real Codex sandbox denied out-of-workspace write")'] : codexArgs(run,prepared.output);
      if(!probe) prepared.mounts.push({source:root+'/codex-fixture',target:'/pnpm/bin/codex',readOnly:true});
      await writeFile(root+'/data/runner-plans/'+id+'.json',JSON.stringify({id,args,cwd:prepared.cwd,prompt:task.prompt,mounts:prepared.mounts,expires:Date.now()+120000,sandbox:mode}));
    `
    for (const mode of ['yolo', 'read-only', 'workspace-write']) {
      const id = randomUUID()
      runs.push(id)
      docker('exec', manager, 'node', '--import', 'tsx', '--input-type=module', '-e', prepare, JSON.stringify({ root, id, mode, probe: mode === 'workspace-write' }))
      const start = await fetch(`${url}/runs/${id}`, { method: 'POST', headers })
      assert.equal(start.status, 200, await start.text())
      const result = await fetch(`${url}/runs/${id}/wait`, { method: 'POST', headers }).then(response => response.json())
      const logs = await fetch(`${url}/runs/${id}/logs`, { headers }).then(response => response.text())
      assert.equal(result.StatusCode, 0, logs)
      assert.match(logs, /Isolation assertions passed|Real Codex sandbox denied/)
      const inspect = JSON.parse(docker('inspect', `leo-run-${id}`))[0]
      assert.equal(inspect.Config.User, '1000:1000')
      assert.equal(inspect.HostConfig.Privileged, false)
      assert.equal(inspect.HostConfig.ReadonlyRootfs, true)
      assert.deepEqual(inspect.HostConfig.CapDrop, ['ALL'])
      assert.ok(!inspect.Mounts.some(mount => mount.Source === '/var/run/docker.sock'))
      await fetch(`${url}/runs/${id}`, { method: 'DELETE', headers })
      process.stdout.write(`${mode}: real container access restrictions passed\n`)
    }
    const clientId = randomUUID()
    runs.push(clientId)
    docker('exec', manager, 'node', '--import', 'tsx', '--input-type=module', '-e', prepare, JSON.stringify({ root, id: clientId, mode: 'yolo' }))
    const brokerIp = JSON.parse(docker('inspect', broker))[0].NetworkSettings.Networks.bridge.IPAddress
    const loader = docker('exec', manager, 'node', '--input-type=module', '-e', 'console.log(import.meta.resolve("tsx"))')
    const clientOutput = docker('exec', '--workdir', `${root}/project`, '-e', `RUNNER_URL=http://${brokerIp}:4311`, '-e', 'RUNNER_TOKEN=runner-smoke-secret', manager, 'node', '--import', loader, '/app/server/runner-client.ts', clientId)
    assert.match(clientOutput, /Isolation assertions passed/)
    assert.throws(() => docker('inspect', `leo-run-${clientId}`))
    process.stdout.write('Remote client: streamed events, exit status, workspace module resolution, and container cleanup passed\n')
    const id = randomUUID()
    runs.push(id)
    docker('exec', manager, 'node', '--import', 'tsx', '--input-type=module', '-e', prepare, JSON.stringify({ root, id, mode: 'yolo', hang: true }))
    assert.equal((await fetch(`${url}/runs/${id}`, { method: 'POST', headers })).status, 200)
    assert.equal((await fetch(`${url}/runs/${id}`, { method: 'DELETE', headers })).status, 200)
    assert.throws(() => docker('inspect', `leo-run-${id}`))
    process.stdout.write('Runner smoke passed: authentication, resource isolation, YOLO, read-only, real Codex sandbox, and cancellation.\n')
  }
  finally {
    for (const name of [...runs.map(id => `leo-run-${id}`), broker, manager]) {
      try {
        docker('rm', '-f', '-v', name)
      }
      catch { /* already removed */ }
    }
    await rm(root, { recursive: true, force: true })
  }
}
main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
