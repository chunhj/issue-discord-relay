import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchHistory, type JobStatus } from '../api/history'
import { mergeFirstPage, type Page } from '../lib/mergeFirstPage'

const POLL_MS = 5000
const ERROR_MESSAGE = '목록을 불러오지 못했습니다.' // 서버 오류 문구는 저장하지 않는다 (T-03-11)
const EMPTY: Page = { items: [], nextBefore: null }

export type StatusFilter = 'ALL' | JobStatus

const queryOf = (status: StatusFilter) => (status === 'ALL' ? {} : { status })

export function useHistory() {
  const [page, setPage] = useState<Page>(EMPTY)
  const [status, setStatusState] = useState<StatusFilter>('ALL')
  const [auto, setAutoState] = useState(true)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const autoRef = useRef(true)
  const moreRef = useRef<AbortController | null>(null) // 더 보기 요청이 진행 중이면 그 컨트롤러
  const fetchHeadRef = useRef<(replace?: boolean) => Promise<void>>(async () => {})

  // 첫 페이지 로드 + 폴링. 이전 요청이 끝난 뒤에만 다음 setTimeout을 건다 (D-12).
  useEffect(() => {
    const timer: { id?: ReturnType<typeof setTimeout> } = {}
    const flight = { active: false }
    const controller = new AbortController()

    // replace=true(새로고침 버튼)는 진행 중인 폴링과 무관하게 첫 페이지로 전체 교체한다. 폴링은 병합형이다.
    const fetchHead = async (replace = false) => {
      if (flight.active && !replace) return
      flight.active = true
      try {
        const head = await fetchHistory(queryOf(status), controller.signal)
        if (controller.signal.aborted) return
        setPage((prev) => (replace ? head : mergeFirstPage(prev, head)))
        setError(null)
        setLastUpdated(new Date())
      } catch {
        if (!controller.signal.aborted) setError(ERROR_MESSAGE)
      } finally {
        flight.active = false
        if (!controller.signal.aborted) setLoading(false)
      }
    }

    const cycle = async (force: boolean) => {
      if (force || (autoRef.current && !document.hidden && !moreRef.current)) await fetchHead()
      if (!controller.signal.aborted) timer.id = setTimeout(() => void cycle(false), POLL_MS)
    }

    const onVisible = () => {
      if (!document.hidden && autoRef.current) void fetchHead()
    }

    fetchHeadRef.current = fetchHead
    document.addEventListener('visibilitychange', onVisible)
    void cycle(true)
    return () => {
      clearTimeout(timer.id)
      controller.abort()
      moreRef.current?.abort()
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [status])

  const setStatus = useCallback((next: StatusFilter) => {
    moreRef.current?.abort()
    moreRef.current = null
    setLoadingMore(false)
    setPage(EMPTY)
    setLoading(true)
    setStatusState(next)
  }, [])

  const setAuto = useCallback((next: boolean) => {
    autoRef.current = next
    setAutoState(next)
  }, [])

  // 새로고침은 전체 재로드: 더 보기로 받은 행도 버리고 첫 페이지만 다시 받아 상태를 갱신한다.
  const refresh = useCallback(() => {
    moreRef.current?.abort() // 이전 커서로 받는 더 보기 응답이 교체된 목록에 붙지 않게 한다
    moreRef.current = null
    setLoadingMore(false)
    return fetchHeadRef.current(true)
  }, [])

  const loadMore = useCallback(async () => {
    const before = page.nextBefore
    if (before === null || moreRef.current) return
    const controller = new AbortController()
    moreRef.current = controller
    setLoadingMore(true)
    try {
      const next = await fetchHistory({ ...queryOf(status), before }, controller.signal)
      if (controller.signal.aborted) return
      setPage((prev) => {
        const seen = new Set(prev.items.map((i) => i.eventId))
        return {
          items: [...prev.items, ...next.items.filter((i) => !seen.has(i.eventId))],
          nextBefore: next.nextBefore,
        }
      })
    } catch {
      if (!controller.signal.aborted) setError(ERROR_MESSAGE)
    } finally {
      if (moreRef.current === controller) {
        moreRef.current = null
        setLoadingMore(false)
      }
    }
  }, [page.nextBefore, status])

  return {
    items: page.items,
    nextBefore: page.nextBefore,
    status,
    setStatus,
    auto,
    setAuto,
    loading,
    loadingMore,
    error,
    lastUpdated,
    refresh,
    loadMore,
  }
}
