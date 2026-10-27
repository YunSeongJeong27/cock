import { josa } from './common'

/** 입금 대조(거래내역·스크린샷), 은행 헤더(컬럼) 설정의 긴 문장. 규칙은 common.ts 맨 위 */
export const depositText = {
  // 거래내역 올리기
  uploadHelp: "은행 앱·인터넷뱅킹의 거래내역조회에서 엑셀이나 PDF로 받아 올려주세요. 토스뱅크는 발급할 때 '입금만 + 엑셀'로 받으면 편합니다. 원본 파일은 저장하지 않습니다.",
  mixedAccountWarning: '입금 받는 계좌를 공구 판매 말고 다른 용도로도 쓰면 대조가 잘 안 될 수 있습니다.',
  // 2026-10-27 사용자 요청 문구
  visibleInfoTip: '거래내역서에 계좌번호와 이름이 가려지지 않고 보이면 대조가 잘 됩니다.',
  filePasswordHelp: '암호가 걸린 PDF나 엑셀이면 올리기 전에 비밀번호를 입력하세요. 비밀번호는 파일을 여는 데만 쓰고 저장하지 않습니다.',
  bankSelectHelp: '고른 은행의 헤더(컬럼)명으로 입금을 읽습니다. 처음에는 공구 입금 계좌의 은행이 골라져 있고 목록에 없는 은행이면 토스뱅크가 골라져 있습니다.',
  uploaded: (saved: number, duplicates: number) =>
    `입금 ${saved}건을 올렸습니다.${duplicates ? ` 이미 올린 입금 ${duplicates}건은 건너뛰었습니다.` : ''}`,
  /** headerRowNo 가 null 이면(PDF) 헤더 행 번호를 말하지 않는다 */
  columnsFound: (bankName: string, headerRowNo: number | null) =>
    `${bankName} 헤더(컬럼)명으로 읽었습니다.${headerRowNo ? ` 헤더는 ${headerRowNo}행입니다.` : ''}`,
  /** excluded: 거래 구분으로 뺀 줄 (skipped 에 들어 있음) */
  previewCounts: (deposits: number, skipped: number, excluded: number, duplicates: number) =>
    `입금 ${deposits}건${excluded ? ` · 캐시백·카드·이자 등이라 뺀 줄 ${excluded}개` : ''}${skipped - excluded ? ` · 입금이 아니라서 건너뛴 줄 ${skipped - excluded}개` : ''}${duplicates ? ` · 이미 올린 입금 ${duplicates}건` : ''}`,
  accountDifferentConfirm: (notice: string) => `${notice}\n\n그래도 올릴까요?`,
  confirmDeleteUpload: '이 업로드의 입금 줄을 지울까요?',

  // 대조 결과
  confirmed: (count: number) => `${count}건 입금 확정했습니다.`,
  confirmHelp: '결과는 "확정"을 눌러야 저장됩니다. 확정하면 입금자명이 그 고객의 별칭으로 저장되어 다음 공구부터 자동 매칭에 쓰입니다.',
  noDeposits: '대조할 입금이 없습니다. 거래내역을 올려주세요.',
  refundCheckHint: '입금하고 취소한 고객이면 누르세요. 주문 목록에 "환불 필요"로 뜹니다.',
  confirmHandled: (name: string, amount: string) =>
    `${name} ${amount} 입금을 "이미 처리 완료된 입금"으로 옮길까요?\n\n대조 결과에서만 빠지고 주문에는 아무 변화가 없습니다. 같은 거래내역을 다시 올려도 다시 나오지 않고 "이미 처리 완료된 입금 보기"에서 되돌릴 수 있습니다.`,
  handledHelp: '대조 결과에서 뺀 입금입니다. 되돌리면 다시 대조 결과에 나옵니다.',
  // 같은 입금 계좌를 쓰는 공구끼리 거래내역 공유 (2026-10-27)
  sharedAccount: (titles: string[]) => {
    const last = titles[titles.length - 1]
    return `같은 입금 계좌를 쓰는 공구 "${titles.join('", "')}"${josa(last, '과/와')} 거래내역을 함께 씁니다. 어느 공구 화면에서 확정하거나 처리 완료해도 함께 반영됩니다.`
  },
  otherRowsHelp: '같은 입금 계좌를 쓰는 다른 공구의 주문으로 보이는 입금입니다. 여기서 확정해도 됩니다.',

  // 대조 결과 기준 설명 (2026-10-26)
  criteria: [
    { status: 'MATCHED', name: '자동 매칭', desc: '입금자명(또는 저장된 별칭·닉네임)과 금액이 맞고 그런 고객이 1명뿐입니다. [자동 매칭 모두 확정]으로 한 번에 확정할 수 있습니다.' },
    { status: 'CHECK', name: '확인 필요', desc: '같은 고객의 미입금 주문이나 입금이 여러 건이거나 입금 시각이 주문 시각과 1시간 넘게 차이 납니다. 금액을 바꾼 주문은 바꾼 시각과 비교합니다. [모두 확정]에서 빠지니 하나씩 보고 확정하세요.' },
    { status: 'SUGGESTED', name: '추천', desc: '이름은 맞는데 금액이 다르면 "금액 차이 ±N원"이 붙습니다. 확인 후 확정하세요.' },
    { status: 'SUGGESTED', name: '금액만 일치', desc: '이름은 다르지만 같은 금액의 미입금 주문과 입금이 딱 1건씩입니다. 가족 이름으로 보낸 경우 등입니다. 같은 금액이 여러 건이면 추천하지 않습니다.' },
    { status: 'AMBIGUOUS', name: '후보 여러 명', desc: '같은 이름의 고객이 2명 이상입니다. 목록에서 직접 골라 확정하세요.' },
    { status: 'UNRELATED', name: '관련 없는 입금', desc: '맞는 주문을 못 찾은 입금입니다. 주문을 직접 골라 확정하거나 [이미 처리 완료된 입금]으로 옮기세요.' },
  ] as { status: string; name: string; desc: string }[],
  criteriaEvidence: '근거는 입금자명 일치 → 저장된 별칭 → 닉네임 포함 → 닉네임 숫자 일치 → 금액만 일치 순으로 강합니다.',
  criteriaFooter: '[확정]을 누르기 전에는 아무것도 저장되지 않습니다. 확정하면 입금자명이 그 고객의 별칭으로 저장되어 다음 공구부터 자동 매칭에 쓰입니다.',

  // 스크린샷
  screenshotHelp: '폰에서 엑셀 받기가 번거로우면 은행앱 거래내역 화면을 캡처해서 여러 장 올리세요. AI가 입금자명·금액을 읽으면 직접 확인·수정한 뒤 저장합니다. 읽기 어려운 글자는 [?]로 표시됩니다. 캡처 이미지는 외부 AI 서비스로 전송되며 읽은 직후 삭제됩니다.',
  withdrawalsSkipped: (count: number) => `출금 ${count}건은 뺐습니다.`,
  screenshotSaved: '스크린샷 입금을 저장했습니다. 아래 대조 결과에 반영됩니다.',
  fixUnreadFirst: '[?] 표시를 고친 뒤 저장할 수 있습니다.',
  visionOff: '스크린샷 글자 읽기가 아직 꺼져 있습니다. 읽은 결과 표에 직접 입력할 수 있습니다.',
  captureLimitReached: '오늘 한도를 다 썼습니다. 직접 입력해 주세요.',

  // 은행 헤더(컬럼) 설정 (2026-10-27)
  bankHeadersHelp: '거래내역을 올리면 고른 은행의 헤더(컬럼)명으로 입금을 읽습니다. 파일의 헤더(컬럼)명과 다르면 [수정]에서 고쳐 주세요.',
  missingBank: '사용하시는 계좌의 은행이 없나요?',
  bankSaved: (name: string) => `${name} 헤더(컬럼)명을 저장했습니다.`,
  confirmRevert: (name: string) => `${name} 헤더(컬럼)명을 원래 값으로 되돌릴까요?`,
  confirmRemoveBank: (name: string) => `${name}${josa(name, '을/를')} 목록에서 지울까요?`,
  typeColumnHint: '거래 구분 헤더(컬럼) 값에 "캐시백", "프로모션", "카드", "이자", "출금", "지급"이 들어간 줄은 고객 입금이 아니라서 뺍니다. 파일에 이 헤더(컬럼)가 없으면 빼지 않고 읽습니다.',
  accountLabelHint: '거래내역서 위쪽에서 계좌번호 앞에 적힌 글자입니다. 적으면 공구 입금 계좌와 같은 계좌의 거래내역인지 확인합니다.',
  // 설정 방법 창
  howToSteps: [
    '은행 앱이나 인터넷뱅킹에서 거래내역을 엑셀이나 PDF로 받습니다.',
    '파일 표 맨 윗줄의 헤더(컬럼)명을 그대로 적습니다. 띄어쓰기는 달라도 됩니다.',
    '입금자명은 보낸 사람 이름, 입금액은 받은 금액, 거래일시는 날짜와 시각이 나오는 헤더(컬럼)입니다.',
    '입금과 출금이 한 헤더(컬럼)에 +와 -로 나오면 그 헤더(컬럼)를 입금액에 적습니다. + 금액만 입금으로 읽습니다.',
    '계좌번호에는 표 위쪽에서 계좌번호 앞에 적힌 글자를 적습니다.',
    '거래 구분에는 입금이나 카드결제처럼 거래 종류가 나오는 헤더(컬럼)를 적습니다. 캐시백·카드·이자 같은 줄은 고객 입금이 아니라서 뺍니다.',
  ],
  exampleNote: '예시 그림의 이름·금액·계좌번호는 지어낸 값입니다. 그림을 누르면 크게 볼 수 있습니다.',
  aliasHelp: '입금을 확정하면 "입금자명 → 고객"이 저장되고 다음 공구부터 같은 입금자명이면 자동으로 그 고객과 매칭합니다. 잘못 저장된 것은 지우세요.',
  confirmRemove: '삭제할까요?',
}
