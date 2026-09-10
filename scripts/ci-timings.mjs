import { execFile } from 'node:child_process'
import process from 'node:process'
import { promisify } from 'node:util'

const exec = promisify(execFile)
const seconds = (start, end) => Math.round((Date.parse(end) - Date.parse(start)) / 1000)
export function summarize(run) {
  const jobs = run.jobs.filter(job => job.startedAt && job.completedAt)
  return {
    url: run.url,
    ref: run.headBranch,
    commit: run.headSha,
    conclusion: run.conclusion,
    elapsedSeconds: seconds(run.createdAt, run.updatedAt),
    runnerSeconds: jobs.reduce((sum, job) => sum + seconds(job.startedAt, job.completedAt), 0),
    jobs: jobs.map(job => ({
      name: job.name,
      conclusion: job.conclusion,
      seconds: seconds(job.startedAt, job.completedAt),
      steps: job.steps.filter(step => step.startedAt && step.completedAt).map(step => ({ name: step.name, seconds: seconds(step.startedAt, step.completedAt) })),
    })),
  }
}

if (import.meta.main) {
  const budget = process.argv.find(arg => arg.startsWith('--budget='))?.slice(9)
  const ids = process.argv.slice(2).filter(arg => /^\d+$/.test(arg))
  if (!ids.length)
    throw new Error('Usage: node scripts/ci-timings.mjs RUN_ID [RUN_ID...] [--budget=SECONDS]')
  const results = await Promise.all(ids.map(async (id) => {
    const { stdout } = await exec('gh', ['run', 'view', id, '--json', 'createdAt,updatedAt,jobs,headSha,headBranch,url,conclusion,status'], { maxBuffer: 4 * 1024 * 1024 })
    const run = JSON.parse(stdout)
    if (run.status !== 'completed')
      throw new Error(`Run ${id} is not complete`)
    return summarize(run)
  }))
  console.log(JSON.stringify(results, null, 2))
  if (budget && results.some(run => run.conclusion !== 'success' || run.elapsedSeconds > Number(budget)))
    process.exitCode = 1
}
