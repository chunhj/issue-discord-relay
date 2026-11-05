import type { JobStatus } from '../api/history'
import { STATUS_LABEL } from '../lib/format'

// 장식용 기호. 의미는 텍스트 라벨이 전달하므로 aria-hidden 처리한다.
const GLYPH: Record<JobStatus, string> = {
  SENT: '✓',
  RETRY_WAIT: '◔',
  FAILED: '✕',
  PROCESSING: '↻',
  PENDING: '○',
}

// 색만으로 상태를 구분하지 않도록 기호와 한국어 텍스트를 항상 함께 보여준다 (D-13, D-15).
export function StatusBadge({ status }: { status: JobStatus }) {
  return (
    <span className={`badge badge-${status.toLowerCase()}`} data-status={status} title={status}>
      <span className="glyph" aria-hidden="true">
        {GLYPH[status]}
      </span>
      {STATUS_LABEL[status]}
    </span>
  )
}
