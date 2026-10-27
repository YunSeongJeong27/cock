import { useEffect } from 'react'
import { depositText as T } from '../../texts/deposit'

interface Step { img: string; text: string }

/** 단계별 그림 (가로로 넘겨 봄). 그림을 누르면 크게 */
function Steps({ steps, start }: { steps: Step[]; start: number }) {
  return (
    <div className="guide-steps">
      {steps.map((s, i) => (
        <figure key={s.img} className="guide-step">
          <a href={`/help/toss/${s.img}.jpg`} target="_blank" rel="noreferrer">
            <img src={`/help/toss/${s.img}.jpg`} alt={s.text} loading="lazy" />
          </a>
          <figcaption><b>{start + i}</b> {s.text}</figcaption>
        </figure>
      ))}
    </div>
  )
}

/** "토스 모바일 파일다운 예시" 창 (2026-10-27 사용자 요청): 토스 앱에서 거래내역 PDF·엑셀을 받는 순서 */
export default function TossFileGuide({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  const g = T.tossGuide
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal modal-wide modal-help" role="dialog" aria-modal="true" aria-labelledby="toss-guide-title" onClick={(e) => e.stopPropagation()}>
        <h3 id="toss-guide-title">토스 모바일 파일다운 예시</h3>
        <p className="small muted">{g.intro}</p>
        <h4>{g.pdfTitle}</h4>
        <Steps steps={g.pdfSteps} start={1} />
        <h4>{g.phoneTitle}</h4>
        <Steps steps={g.phoneSteps} start={g.pdfSteps.length + 1} />
        <h4>{g.excelTitle}</h4>
        <Steps steps={g.excelSteps} start={1} />
        <p className="small">{g.excelNote}</p>
        <div className="row modal-foot" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn primary" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  )
}
