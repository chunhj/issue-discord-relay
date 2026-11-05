import { HistoryTable } from './components/HistoryTable'
import { useHistory } from './hooks/useHistory'

function App() {
  const { items } = useHistory()

  return (
    <main>
      <h1>발송 이력</h1>
      <HistoryTable items={items} />
    </main>
  )
}

export default App
