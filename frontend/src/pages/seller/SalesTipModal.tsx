import { useEffect, useState } from 'react'
import { api, errorMessage } from '../../api'
import { useAuth } from '../../auth'
import { CopyButton } from '../../components/Ui'
import { groupBuyText as T } from '../../texts/groupBuy'
import type { Me } from '../../types'

/**
 * 공구를 만든 직후 뜨는 "제품 판매 TIP" 안내 (2026-10-26 사용자 요청).
 * [다시 보지 않기]를 누르면 판매자 계정에 저장해서 어느 기기에서도 다시 뜨지 않는다. [닫기]는 이번만 닫는다
 */
export default function SalesTipModal({ onClose }: { onClose: () => void }) {
  const { setMe } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  async function hideForever() {
    setBusy(true)
    try {
      setMe(await api.post<Me>('/api/seller/sales-tip/hide'))
      onClose()
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="sales-tip-title" onClick={(e) => e.stopPropagation()}>
        <h3 id="sales-tip-title">💡 제품 판매 TIP</h3>
        <p>{T.salesTip}</p>
        <div className="tip-example">
          <span className="muted small">예시</span>
          <div className="row between">
            <b>"{T.salesTipExample}"</b>
            <CopyButton text={T.salesTipExample} />
          </div>
        </div>
        {error && <div className="small mt" style={{ color: 'var(--danger)' }}>{error}</div>}
        <div className="row between modal-foot">
          <button type="button" className="link-button" disabled={busy} onClick={hideForever}>다시 보지 않기</button>
          <button type="button" className="btn primary" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  )
}
