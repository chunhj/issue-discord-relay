import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'
import type { HistoryItem, HistoryPage } from './api/history'

const base: HistoryItem = {
  eventId: 1,
  deliveryId: 'd-1',
  repo: 'octo/repo',
  issueNumber: 7,
  title: 'first issue',
  htmlUrl: 'https://github.com/octo/repo/issues/7',
  receivedAt: '2026-11-05T01:02:03.123456Z',
  status: 'SENT',
  attempts: 1,
  lastError: null,
  sentAt: '2026-11-05T01:02:04.357Z',
  deliveryLatencyMs: 1234,
}

const page: HistoryPage = {
  items: [
    base,
    {
      ...base,
      eventId: 2,
      deliveryId: 'd-2',
      issueNumber: 8,
      title: 'second issue',
      status: 'FAILED',
      attempts: 5,
      lastError: 'HTTP 404: {"message": "Unknown Webhook", "code": 10015}',
      sentAt: null,
      deliveryLatencyMs: null,
    },
  ],
  nextBefore: null,
}

const ok = (p: HistoryPage) => ({ ok: true, status: 200, json: async () => p })
const params = (call: unknown[]) => new URL(String(call[0]), 'http://x').searchParams
const mk = (eventId: number, over: Partial<HistoryItem> = {}): HistoryItem => ({
  ...base,
  eventId,
  deliveryId: `d-${eventId}`,
  issueNumber: eventId,
  title: `issue ${eventId}`,
  ...over,
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

it('renders_first_page_rows_from_the_api', async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => page })
  vi.stubGlobal('fetch', fetchMock)

  render(<App />)
  const sentRow = (await screen.findByText(/first issue/)).closest('tr')!

  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(String(fetchMock.mock.calls[0][0])).toMatch(/^\/api\/history/)
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('발송 이력')
  for (const h of ['수신 시각', '저장소', '이슈', '상태', '시도', '마지막 오류', '발송 지연']) {
    expect(screen.getByRole('columnheader', { name: h })).toBeInTheDocument()
  }

  expect(within(sentRow).getByText('발송 완료')).toBeInTheDocument()
  expect(within(sentRow).getByText('1')).toBeInTheDocument()
  expect(within(sentRow).getByText('1.2 s')).toBeInTheDocument()

  const failedRow = screen.getByText(/second issue/).closest('tr')!
  expect(within(failedRow).getByText('실패')).toBeInTheDocument()
  expect(within(failedRow).getByText('5')).toBeInTheDocument()
  expect(within(failedRow).getByText(/Unknown Webhook/)).toBeInTheDocument()
})

it('empty_state_when_there_are_no_events', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok({ items: [], nextBefore: null })))
  render(<App />)
  expect(await screen.findByText('아직 수신한 이벤트가 없습니다')).toBeInTheDocument()
  expect(document.querySelectorAll('tbody tr')).toHaveLength(0)
})

it('first_load_error_shows_message_and_retry', async () => {
  const fetchMock = vi
    .fn()
    .mockRejectedValueOnce(new Error('internal stack trace'))
    .mockResolvedValue(ok(page))
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('목록을 불러오지 못했습니다.')
  expect(alert).not.toHaveTextContent('internal stack trace')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByText(/first issue/)).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('refresh_error_keeps_previous_rows', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(ok(page)).mockRejectedValue(new Error('down'))
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  await screen.findByText(/first issue/)
  fireEvent.click(screen.getByRole('button', { name: '새로고침' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('목록을 불러오지 못했습니다.')
  expect(screen.getByText(/first issue/)).toBeInTheDocument()
})

it('load_more_sends_before_and_appends', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    params([url]).has('before')
      ? Promise.resolve(ok({ items: [mk(40, { title: 'older issue' })], nextBefore: null }))
      : Promise.resolve(ok({ items: [mk(60, { title: 'newer issue' })], nextBefore: 50 })),
  )
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  await screen.findByText(/newer issue/)
  fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
  await screen.findByText(/older issue/)
  expect(params(fetchMock.mock.calls.at(-1)!).get('before')).toBe('50')
  const rows = document.querySelectorAll('tbody tr')
  expect(rows[0]).toHaveTextContent('newer issue')
  expect(rows[1]).toHaveTextContent('older issue')
  expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
})

it('status_filter_sends_status_and_replaces_list', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    Promise.resolve(
      ok(
        params([url]).get('status') === 'FAILED'
          ? { items: [mk(9, { title: 'failed only', status: 'FAILED' })], nextBefore: null }
          : page,
      ),
    ),
  )
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  await screen.findByText(/first issue/)
  const select = screen.getByLabelText('상태 필터')
  expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual([
    '전체',
    '대기',
    '처리 중',
    '재시도 대기',
    '발송 완료',
    '실패',
  ])
  fireEvent.change(select, { target: { value: 'FAILED' } })
  await screen.findByText(/failed only/)
  const last = fetchMock.mock.calls.at(-1)!
  expect(params(last).get('status')).toBe('FAILED')
  expect(params(last).has('before')).toBe(false)
  expect(screen.queryByText(/first issue/)).not.toBeInTheDocument()
})

it('auto_refresh_is_on_by_default_and_can_be_switched_off', async () => {
  vi.useFakeTimers()
  const fetchMock = vi.fn().mockResolvedValue(ok(page))
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  const box = screen.getByLabelText('자동 갱신 (5초)')
  expect(box).toBeChecked()
  await act(async () => void (await vi.advanceTimersByTimeAsync(0)))
  expect(fetchMock).toHaveBeenCalledTimes(1)
  await act(async () => void (await vi.advanceTimersByTimeAsync(5000)))
  expect(fetchMock).toHaveBeenCalledTimes(2)
  fireEvent.click(box)
  expect(box).not.toBeChecked()
  await act(async () => void (await vi.advanceTimersByTimeAsync(15000)))
  expect(fetchMock).toHaveBeenCalledTimes(2)
})
