import { useCallback, useEffect, useState } from 'react'
import { fetchHistory, type HistoryItem } from '../api/history'

// 첫 페이지만 불러온다. 폴링, 필터, 더 보기는 plan 03-04에서 확장한다.
export function useHistory() {
  const [items, setItems] = useState<HistoryItem[]>([])
  const [nextBefore, setNextBefore] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    fetchHistory({}, controller.signal)
      .then((page) => {
        setItems(page.items)
        setNextBefore(page.nextBefore)
        setError(null)
        setLoading(false)
      })
      .catch(() => {
        if (controller.signal.aborted) return
        setError('목록을 불러오지 못했습니다.')
        setLoading(false)
      })
    return () => controller.abort()
  }, [reloadKey])

  const reload = useCallback(() => {
    setLoading(true)
    setReloadKey((k) => k + 1)
  }, [])

  return { items, nextBefore, loading, error, reload }
}
