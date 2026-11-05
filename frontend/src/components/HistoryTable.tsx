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
          <th className="c-time">수신 시각</th>
          <th className="c-repo">저장소</th>
          <th className="c-issue">이슈</th>
          <th className="c-status">상태</th>
          <th className="c-attempts">시도</th>
          <th className="c-error">마지막 오류</th>
          <th className="c-latency">발송 지연</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => {
          const label = (
            <>
              <b className="no">{`#${item.issueNumber}`}</b> {item.title}
            </>
          )
          const latency = formatLatency(item.deliveryLatencyMs)
          return (
            <tr key={item.eventId} className={`row-${item.status.toLowerCase()}`}>
              <td className="c-time">{formatTime(item.receivedAt)}</td>
              <td className="c-repo">{item.repo}</td>
              <td className="c-issue issue">
                {isHttps(item.htmlUrl) ? (
                  <a href={item.htmlUrl} target="_blank" rel="noopener noreferrer">
                    {label}
                  </a>
                ) : (
                  label
                )}
              </td>
              <td className="c-status">
                <StatusBadge status={item.status} />
              </td>
              <td className="c-attempts" data-label="시도">
                {item.attempts}
              </td>
              {item.lastError === null ? (
                <td className="c-error dim">{DASH}</td>
              ) : (
                <td className="c-error">
                  <span className="err" title={item.lastError}>
                    {item.lastError}
                  </span>
                </td>
              )}
              <td className={item.deliveryLatencyMs === null ? 'c-latency dim' : 'c-latency'} data-label="발송 지연">
                {latency}
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
