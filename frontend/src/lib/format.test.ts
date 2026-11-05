import { expect, it } from 'vitest'
import { formatLatency, formatTime, STATUS_LABEL } from './format'

it('formats latency', () => {
  expect(formatLatency(null)).toBe('—')
  expect(formatLatency(0)).toBe('0 ms')
  expect(formatLatency(999)).toBe('999 ms')
  expect(formatLatency(1000)).toBe('1.0 s')
  expect(formatLatency(12345)).toBe('12.3 s')
})

it('formats microsecond ISO timestamps without Invalid Date', () => {
  const text = formatTime('2026-11-05T01:02:03.123456Z')
  expect(text).not.toBe('')
  expect(text).not.toContain('Invalid')
})

it('labels exactly the five statuses in Korean', () => {
  expect(STATUS_LABEL).toEqual({
    PENDING: '대기',
    PROCESSING: '처리 중',
    RETRY_WAIT: '재시도 대기',
    SENT: '발송 완료',
    FAILED: '실패',
  })
})
