import { HistoryTable } from './components/HistoryTable'
import { Toolbar } from './components/Toolbar'
import { useHistory } from './hooks/useHistory'

function App() {
  const h = useHistory()

  return (
    <main>
      <h1>발송 이력</h1>
      <Toolbar
        lastUpdated={h.lastUpdated}
        onRefresh={h.refresh}
        auto={h.auto}
        onAutoChange={h.setAuto}
        status={h.status}
        onStatusChange={h.setStatus}
      />
      {h.error && (
        <div role="alert" className="banner">
          <span>{h.error}</span>
          <button type="button" onClick={h.refresh}>
            다시 시도
          </button>
        </div>
      )}
      {h.loading && h.items.length === 0 && <p>불러오는 중…</p>}
      {!h.loading && !h.error && h.items.length === 0 && <p>아직 수신한 이벤트가 없습니다</p>}
      <HistoryTable items={h.items} />
      {h.nextBefore !== null && (
        <button type="button" className="more" onClick={() => void h.loadMore()} disabled={h.loadingMore}>
          {h.loadingMore ? '불러오는 중…' : '더 보기'}
        </button>
      )}
    </main>
  )
}

export default App
