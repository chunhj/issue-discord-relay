export type JobStatus = 'PENDING' | 'PROCESSING' | 'RETRY_WAIT' | 'SENT' | 'FAILED'

// 백엔드 GET /api/history 응답 계약(plan 03-01)을 그대로 옮긴 손수 작성 타입 (D-16).
// 시각은 마이크로초가 올 수 있어 ISO 문자열로 유지한다.
export interface HistoryItem {
  eventId: number
  deliveryId: string
  repo: string
  issueNumber: number
  title: string
  htmlUrl: string
  receivedAt: string
  status: JobStatus
  attempts: number
  lastError: string | null
  sentAt: string | null
  deliveryLatencyMs: number | null
}

export interface HistoryPage {
  items: HistoryItem[]
  nextBefore: number | null
}

export async function fetchHistory(
  params: { status?: JobStatus; before?: number; limit?: number },
  signal?: AbortSignal,
): Promise<HistoryPage> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) query.set(key, String(value))
  }
  const qs = query.toString()
  const response = await fetch(qs ? `/api/history?${qs}` : '/api/history', { signal })
  // 응답 본문과 상태 문구는 사용자에게 노출하지 않는다.
  if (!response.ok) throw new Error('history request failed')
  return (await response.json()) as HistoryPage
}
