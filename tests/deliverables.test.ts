import type { Deliverable } from '../shared/artifacts'
import type { ActivityEntry } from '../src/activity'
import { describe, expect, it } from 'vitest'
import { artifactUrl, latestArtifacts } from '../shared/artifacts'
import { deliveryEntries } from '../src/deliverables'

const image = (id: string, version: number, createdAt: number, messageId = 'turn1'): Deliverable => ({ id, runId: 'run', messageId, key: 'mobile', version, title: 'Mobile', name: 'mobile.png', kind: 'image', mediaType: 'image/png', size: 12, createdAt, url: 'javascript:alert(1)', group: '', previewStatus: 'none' })
describe('published deliverables', () => {
  it('keeps versions with their answer while the library selects the latest revision', () => {
    const entries: ActivityEntry[] = [{ kind: 'message', role: 'user', id: 'a', time: 1, text: 'Create' }, { kind: 'message', id: 'b', time: 5, text: 'Done' }, { kind: 'message', role: 'user', id: 'c', time: 6, text: 'Improve' }, { kind: 'message', id: 'd', time: 10, text: 'Improved' }]
    const files = [image('old', 1, 3), image('new', 2, 8, 'turn2')]
    const result = deliveryEntries(entries, files)
    expect(result.map(entry => entry.id)).toEqual(['a', 'b', 'deliverables:1', 'c', 'd', 'deliverables:3'])
    expect(latestArtifacts(files).map(item => item.id)).toEqual(['new'])
  })
  it('shows published files before an unfinished answer and never crosses a later user message', () => {
    const entries: ActivityEntry[] = [{ kind: 'message', id: 'a', time: 1, text: 'Working' }, { kind: 'message', role: 'user', id: 'b', time: 5, text: 'Next' }]
    expect(deliveryEntries(entries, [image('file', 1, 3)]).map(entry => entry.kind)).toEqual(['message', 'deliverables', 'message'])
    expect(deliveryEntries([], [image('file', 1, 3)])[0].kind).toBe('deliverables')
    expect(artifactUrl(image('file', 1, 3))).toBe('/api/runs/run/artifacts/file')
  })
})
