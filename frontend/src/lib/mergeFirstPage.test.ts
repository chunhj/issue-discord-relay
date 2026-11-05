import { expect, it } from 'vitest'
import type { HistoryItem } from '../api/history'
import { mergeFirstPage } from './mergeFirstPage'

// eventId 가 클수록 늦게 수신된 행이다. 기본 수신 시각은 id 초 단위로 증가한다.
const row = (eventId: number, over: Partial<HistoryItem> = {}): HistoryItem => ({
  eventId,
  deliveryId: `d-${eventId}`,
  repo: 'octo/repo',
  issueNumber: eventId,
  title: `issue ${eventId}`,
  htmlUrl: `https://github.com/octo/repo/issues/${eventId}`,
  receivedAt: new Date(Date.UTC(2026, 10, 5, 0, 0, eventId)).toISOString(),
  status: 'PENDING',
  attempts: 0,
  lastError: null,
  sentAt: null,
  deliveryLatencyMs: null,
  ...over,
})
const ids = (items: HistoryItem[]) => items.map((i) => i.eventId)

it('puts a new row on top', () => {
  const merged = mergeFirstPage(
    { items: [row(2), row(1)], nextBefore: null },
    { items: [row(3), row(2), row(1)], nextBefore: null },
  )
  expect(ids(merged.items)).toEqual([3, 2, 1])
  expect(merged.nextBefore).toBeNull()
})

it('replaces a changed row in place', () => {
  const merged = mergeFirstPage(
    { items: [row(2), row(1)], nextBefore: null },
    { items: [row(2, { status: 'SENT' }), row(1)], nextBefore: null },
  )
  expect(merged.items[0].status).toBe('SENT')
  expect(ids(merged.items)).toEqual([2, 1])
})

it('keeps older rows loaded through load-more', () => {
  const merged = mergeFirstPage(
    { items: [10, 9, 8, 7, 6].map((i) => row(i)), nextBefore: 6 },
    { items: [11, 10, 9].map((i) => row(i)), nextBefore: 9 },
  )
  expect(ids(merged.items)).toEqual([11, 10, 9, 8, 7, 6])
  expect(merged.nextBefore).toBe(6)
})

it('returns the head as is when the head reaches the end', () => {
  const head = { items: [row(3), row(2)], nextBefore: null }
  const merged = mergeFirstPage({ items: [row(3), row(2), row(1)], nextBefore: null }, head)
  expect(merged).toEqual(head)
})

it('drops a newer prev row that is missing from the head', () => {
  const merged = mergeFirstPage(
    { items: [row(9), row(5), row(4)], nextBefore: 4 },
    { items: [row(8), row(7), row(6)], nextBefore: 6 },
  )
  expect(ids(merged.items)).toEqual([8, 7, 6, 5, 4])
  expect(merged.nextBefore).toBe(4)
})

it('uses the head cursor when nothing older was kept', () => {
  const merged = mergeFirstPage(
    { items: [row(9)], nextBefore: 9 },
    { items: [row(8), row(7)], nextBefore: 7 },
  )
  expect(ids(merged.items)).toEqual([8, 7])
  expect(merged.nextBefore).toBe(7)
})

it('orders rows with an identical receivedAt by eventId descending', () => {
  const t = '2026-11-05T00:00:00.000Z'
  const same = (id: number) => row(id, { receivedAt: t })
  const merged = mergeFirstPage(
    { items: [same(11), same(10)], nextBefore: 10 },
    { items: [same(12), same(11)], nextBefore: 11 },
  )
  expect(ids(merged.items)).toEqual([12, 11, 10])
  expect(merged.nextBefore).toBe(10)
})
