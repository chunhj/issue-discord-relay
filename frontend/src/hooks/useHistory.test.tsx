import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { HistoryItem, HistoryPage } from '../api/history'
import { useHistory } from './useHistory'

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
const ok = (page: HistoryPage) => ({ ok: true, status: 200, json: async () => page })
const params = (call: unknown[]) => new URL(String(call[0]), 'http://x').searchParams

let hidden = false
const setHidden = (value: boolean) => {
  hidden = value
}
const flush = (ms = 0) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)))

beforeEach(() => {
  vi.useFakeTimers()
  hidden = false
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

it('loads the first page without a query string and records lastUpdated', async () => {
  const fetchMock = vi.fn().mockResolvedValue(ok({ items: [row(2), row(1)], nextBefore: null }))
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useHistory())
  expect(result.current.loading).toBe(true)
  await flush()
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/history')
  expect(ids(result.current.items)).toEqual([2, 1])
  expect(result.current.loading).toBe(false)
  expect(result.current.lastUpdated).toBeInstanceOf(Date)
})

it('polls the first page every 5 s and merges it', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(ok({ items: [row(1)], nextBefore: null }))
    .mockResolvedValue(ok({ items: [row(2), row(1, { status: 'SENT' })], nextBefore: null }))
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useHistory())
  await flush()
  await flush(4999)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  await flush(1)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(ids(result.current.items)).toEqual([2, 1])
  expect(result.current.items[1].status).toBe('SENT')
})

it('never overlaps requests while one is unsettled', async () => {
  const fetchMock = vi.fn().mockReturnValue(new Promise(() => {}))
  vi.stubGlobal('fetch', fetchMock)
  renderHook(() => useHistory())
  await flush(15000)
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

it('stops polling when auto is off and resumes when it is on again', async () => {
  const fetchMock = vi.fn().mockResolvedValue(ok({ items: [row(1)], nextBefore: null }))
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useHistory())
  await flush()
  expect(result.current.auto).toBe(true)
  act(() => result.current.setAuto(false))
  await flush(10000)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  act(() => result.current.setAuto(true))
  await flush(5000)
  expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2)
})

it('pauses while the tab is hidden and refreshes once when it becomes visible', async () => {
  const fetchMock = vi.fn().mockResolvedValue(ok({ items: [row(1)], nextBefore: null }))
  vi.stubGlobal('fetch', fetchMock)
  renderHook(() => useHistory())
  await flush()
  setHidden(true)
  await flush(15000)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  setHidden(false)
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

it('keeps items on a failed poll with a fixed message and clears it on the next success', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(ok({ items: [row(1)], nextBefore: null }))
    .mockRejectedValueOnce(new Error('secret server detail'))
    .mockResolvedValue(ok({ items: [row(1)], nextBefore: null }))
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useHistory())
  await flush()
  await flush(5000)
  expect(result.current.error).toBe('목록을 불러오지 못했습니다.')
  expect(ids(result.current.items)).toEqual([1])
  await flush(5000)
  expect(result.current.error).toBeNull()
})

it('setStatus requests status without before, replaces the list and ignores late old responses', async () => {
  let resolveAll!: (page: HistoryPage) => void
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    params([url]).get('status') === 'FAILED'
      ? Promise.resolve(ok({ items: [row(7, { status: 'FAILED' })], nextBefore: null }))
      : new Promise((resolve) => (resolveAll = (page) => resolve(ok(page)))),
  )
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useHistory())
  act(() => result.current.setStatus('FAILED'))
  await flush()
  const last = fetchMock.mock.calls.at(-1)!
  expect(params(last).get('status')).toBe('FAILED')
  expect(params(last).has('before')).toBe(false)
  expect(ids(result.current.items)).toEqual([7])
  await act(async () => resolveAll({ items: [row(1), row(2)], nextBefore: null }))
  expect(ids(result.current.items)).toEqual([7])
})

it('loadMore requests before with the active status, appends without duplicates and skips polls meanwhile', async () => {
  let resolveMore!: (page: HistoryPage) => void
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    params([url]).has('before')
      ? new Promise((resolve) => (resolveMore = (page) => resolve(ok(page))))
      : Promise.resolve(ok({ items: [row(60), row(55)], nextBefore: 50 })),
  )
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useHistory())
  await flush()
  act(() => result.current.setStatus('SENT'))
  await flush()
  expect(result.current.nextBefore).toBe(50)
  act(() => void result.current.loadMore())
  const moreCall = fetchMock.mock.calls.at(-1)!
  expect(params(moreCall).get('before')).toBe('50')
  expect(params(moreCall).get('status')).toBe('SENT')
  const callsBefore = fetchMock.mock.calls.length
  await flush(5000)
  expect(fetchMock.mock.calls.length).toBe(callsBefore)
  await act(async () => resolveMore({ items: [row(55), row(40)], nextBefore: null }))
  expect(ids(result.current.items)).toEqual([60, 55, 40])
  expect(result.current.nextBefore).toBeNull()
  expect(result.current.loadingMore).toBe(false)
})

it('keeps items and sets the fixed error when loadMore fails', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    params([url]).has('before')
      ? Promise.reject(new Error('boom'))
      : Promise.resolve(ok({ items: [row(60)], nextBefore: 50 })),
  )
  vi.stubGlobal('fetch', fetchMock)
  const { result } = renderHook(() => useHistory())
  await flush()
  await act(async () => void (await result.current.loadMore()))
  expect(ids(result.current.items)).toEqual([60])
  expect(result.current.error).toBe('목록을 불러오지 못했습니다.')
})
