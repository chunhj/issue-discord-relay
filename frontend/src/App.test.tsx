import { render, screen, within } from '@testing-library/react'
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

afterEach(() => vi.unstubAllGlobals())

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
