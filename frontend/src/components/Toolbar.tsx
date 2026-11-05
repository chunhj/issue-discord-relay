import type { JobStatus } from '../api/history'
import type { StatusFilter } from '../hooks/useHistory'
import { formatTime, STATUS_LABEL } from '../lib/format'

interface ToolbarProps {
  lastUpdated: Date | null
  onRefresh: () => void
  auto: boolean
  onAutoChange: (auto: boolean) => void
  status: StatusFilter
  onStatusChange: (status: StatusFilter) => void
}

export function Toolbar({ lastUpdated, onRefresh, auto, onAutoChange, status, onStatusChange }: ToolbarProps) {
  return (
    <div className="toolbar">
      <div>
        <h1>발송 이력</h1>
        <p className="updated">마지막 갱신 {lastUpdated ? formatTime(lastUpdated.toISOString()) : '—'}</p>
      </div>
      <div className="controls">
        <label>
          <input
            className="switch"
            type="checkbox"
            checked={auto}
            onChange={(e) => onAutoChange(e.target.checked)}
          />
          자동 갱신 (5초)
        </label>
        <label>
          상태 필터
          <select value={status} onChange={(e) => onStatusChange(e.target.value as StatusFilter)}>
            <option value="ALL">전체</option>
            {(Object.keys(STATUS_LABEL) as JobStatus[]).map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s]}
              </option>
            ))}
          </select>
        </label>
        <button type="button" onClick={onRefresh}>
          새로고침
        </button>
      </div>
    </div>
  )
}
