import type { HistoryItem } from '../api/history'
import { formatLatency, formatTime } from '../lib/format'
import { StatusBadge } from './StatusBadge'

const DASH = '—'

// 이슈 링크는 https URL일 때만 앵커로 만든다 (T-03-04).
function isHttps(url: string): boolean {
  try {
    return new URL(url).protocol === 'https:'
  } catch {
    return false
  }
}

export function HistoryTable({ items }: { items: HistoryItem[] }) {
  return (
    <table>
      <thead>
        <tr>
          <th>수신 시각</th>
          <th>저장소</th>
          <th>이슈</th>
          <th>상태</th>
          <th>시도</th>
          <th>마지막 오류</th>
          <th>발송 지연</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => {
          const label = `#${item.issueNumber} ${item.title}`
          return (
            <tr key={item.eventId}>
              <td>{formatTime(item.receivedAt)}</td>
              <td>{item.repo}</td>
              <td>
                {isHttps(item.htmlUrl) ? (
                  <a href={item.htmlUrl} target="_blank" rel="noopener noreferrer">
                    {label}
                  </a>
                ) : (
                  label
                )}
              </td>
              <td>
                <StatusBadge status={item.status} />
              </td>
              <td>{item.attempts}</td>
              <td className="last-error" title={item.lastError ?? undefined}>
                {item.lastError ?? DASH}
              </td>
              <td>{formatLatency(item.deliveryLatencyMs)}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
