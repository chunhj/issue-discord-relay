import type { HistoryItem } from '../api/history'

export interface Page {
  items: HistoryItem[]
  nextBefore: number | null
}

// shortcut: 순서를 receivedAt(밀리초로 파싱)과 eventId로 비교하므로 같은 밀리초 안에서 id 순서와
// 시각 순서가 어긋난 두 행은 한 폴링 주기 동안 잘못 놓일 수 있다. 또 head 창 아래에 있는 행은
// 필터에서 빠져도 다음 전체 로드까지 남는다. 필터 변경 또는 새로고침(전체 재로드)이 해소 경로다.
const olderThan = (a: HistoryItem, b: HistoryItem) => {
  const ta = Date.parse(a.receivedAt)
  const tb = Date.parse(b.receivedAt)
  return ta < tb || (ta === tb && a.eventId < b.eventId)
}

// 새로 받은 첫 페이지(head)를 이미 불러온 목록(prev) 위에 합친다. 더 보기로 받은 오래된 행은 남긴다.
export function mergeFirstPage(prev: Page, head: Page): Page {
  if (head.nextBefore === null) return head
  const tail = head.items[head.items.length - 1]
  const kept = tail ? prev.items.filter((item) => olderThan(item, tail)) : []
  return {
    items: [...head.items, ...kept],
    nextBefore: kept.length > 0 ? prev.nextBefore : head.nextBefore,
  }
}
