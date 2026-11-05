import type { JobStatus } from '../api/history'

export const STATUS_LABEL: Record<JobStatus, string> = {
  PENDING: '대기',
  PROCESSING: '처리 중',
  RETRY_WAIT: '재시도 대기',
  SENT: '발송 완료',
  FAILED: '실패',
}

export function formatLatency(ms: number | null): string {
  if (ms === null) return '—'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

// 시간대 옵션을 주지 않아 브라우저 로컬 시간대가 적용된다 (D-13).
const timeFormat = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' })

// 마이크로초 자리가 있는 ISO 문자열도 new Date가 받아들인다 (RESEARCH Pitfall 9).
export function formatTime(iso: string): string {
  return timeFormat.format(new Date(iso))
}
