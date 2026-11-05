import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import type { HistoryItem, JobStatus } from '../api/history'
import { HistoryTable } from './HistoryTable'

const item = (over: Partial<HistoryItem>): HistoryItem => ({
  eventId: 1,
  deliveryId: 'd-1',
  repo: 'octo/repo',
  issueNumber: 7,
  title: 'a title',
  htmlUrl: 'https://github.com/octo/repo/issues/7',
  receivedAt: '2026-11-05T01:02:03.123456Z',
  status: 'PENDING',
  attempts: 0,
  lastError: null,
  sentAt: null,
  deliveryLatencyMs: null,
  ...over,
})

const LABELS: Record<JobStatus, string> = {
  PENDING: '대기',
  PROCESSING: '처리 중',
  RETRY_WAIT: '재시도 대기',
  SENT: '발송 완료',
  FAILED: '실패',
}

it.each(Object.entries(LABELS))('shows %s as Korean text plus data-status', (status, label) => {
  const { container } = render(<HistoryTable items={[item({ status: status as JobStatus })]} />)
  expect(screen.getByText(label)).toBeInTheDocument()
  expect(container.querySelector(`[data-status="${status}"]`)).toHaveTextContent(label)
})

it('shows latency, attempts and last error, with em dashes for nulls', () => {
  render(
    <HistoryTable
      items={[
        item({ eventId: 1, title: 'sent one', status: 'SENT', attempts: 3, deliveryLatencyMs: 1234 }),
        item({ eventId: 2, title: 'failed one', status: 'FAILED', attempts: 5, lastError: 'HTTP 500' }),
      ]}
    />,
  )
  const sent = screen.getByText(/sent one/).closest('tr')!
  expect(within(sent).getByText('1.2 s')).toBeInTheDocument()
  expect(within(sent).getByText('3')).toBeInTheDocument()
  expect(within(sent).getAllByText('—')).toHaveLength(1) // lastError only
  const failed = screen.getByText(/failed one/).closest('tr')!
  expect(within(failed).getByText('HTTP 500')).toBeInTheDocument()
  expect(within(failed).getByText('5')).toBeInTheDocument()
  expect(within(failed).getAllByText('—')).toHaveLength(1) // latency only
})

it('links only https issue urls, with noopener noreferrer', () => {
  render(
    <HistoryTable
      items={[
        item({ eventId: 1, title: 'safe' }),
        item({ eventId: 2, title: 'evil', htmlUrl: 'javascript:alert(1)' }),
        item({ eventId: 3, title: 'plain', htmlUrl: 'http://github.com/octo/repo/issues/7' }),
      ]}
    />,
  )
  const link = screen.getByRole('link', { name: /safe/ })
  expect(link).toHaveAttribute('href', 'https://github.com/octo/repo/issues/7')
  expect(link).toHaveAttribute('target', '_blank')
  expect(link.getAttribute('rel')).toMatch(/noopener/)
  expect(link.getAttribute('rel')).toMatch(/noreferrer/)
  expect(screen.getAllByRole('link')).toHaveLength(1)
  expect(screen.getByText(/#7 evil/)).toBeInTheDocument()
  expect(screen.getByText(/#7 plain/)).toBeInTheDocument()
})
