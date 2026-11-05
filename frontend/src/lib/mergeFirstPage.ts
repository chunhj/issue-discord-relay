import type { HistoryItem } from '../api/history'

export interface Page {
  items: HistoryItem[]
  nextBefore: number | null
}

// shortcut: 순서를 receivedAt(밀리초로 파싱)과 eventId로 비교하므로 같은 밀리초 안에서 id 순서와
// 시각 순서가 어긋난 두 행은 한 폴링 주기 동안 잘못 놓일 수 있다. 또 head 창 아래에 있는 행은
// 필터에서 빠져도 다음 전체 로드까지 남는다. 폴링은 병합형이고, 새로고침 버튼(첫 페이지 전체 교체)과
// 필터 변경이 해소 경로다.
const olderThan = (a: HistoryItem, b: HistoryItem) => {
  const ta = Date.parse(a.receivedAt)
  const tb = Date.parse(b.receivedAt)
  return ta < tb || (ta === tb && a.eventId < b.eventId)
}

// 새로 받은 첫 페이지(head)를 이미 불러온 목록(prev) 위에 합친다. 더 보기로 받은 오래된 행은 남긴다.
export function mergeFirstPage(prev: Page, head: Page): Page {
  if (head.nextBefore === null) return head
  const tail = head.items[head.items.length - 1]
  const newestPrev = prev.items[0]
  // head 와 prev 가 이어지지 않으면(prev 의 최신 행이 head 꼬리보다 오래됨) 사이 행이 숨은 구간이 된다.
  // 병합하지 않고 head 로 교체해 nextBefore 를 head 것으로 되돌린다. 더 보기가 그 구간부터 다시 채운다.
  if (!tail || !newestPrev || olderThan(newestPrev, tail)) return head
  const kept = prev.items.filter((item) => olderThan(item, tail))
  return {
    items: [...head.items, ...kept],
    nextBefore: kept.length > 0 ? prev.nextBefore : head.nextBefore,
  }
}
