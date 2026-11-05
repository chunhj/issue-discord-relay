import type { JobStatus } from '../api/history'
import { STATUS_LABEL } from '../lib/format'

// 색만으로 상태를 구분하지 않도록 한국어 텍스트를 항상 함께 보여준다 (D-13, D-15).
export function StatusBadge({ status }: { status: JobStatus }) {
  return (
    <span className={`badge badge-${status.toLowerCase()}`} data-status={status} title={status}>
      {STATUS_LABEL[status]}
    </span>
  )
}
