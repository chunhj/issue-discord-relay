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
  const fetchHeadRef = useRef<() => Promise<void>>(async () => {})

  // 첫 페이지 로드 + 폴링. 이전 요청이 끝난 뒤에만 다음 setTimeout을 건다 (D-12).
  useEffect(() => {
    const timer: { id?: ReturnType<typeof setTimeout> } = {}
    const flight = { active: false }
    const controller = new AbortController()

    const fetchHead = async () => {
      if (flight.active) return
      flight.active = true
      try {
        const head = await fetchHistory(queryOf(status), controller.signal)
        if (controller.signal.aborted) return
        setPage((prev) => mergeFirstPage(prev, head))
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

  const refresh = useCallback(() => fetchHeadRef.current(), [])

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
