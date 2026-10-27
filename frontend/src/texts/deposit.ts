/** 입금 대조(거래내역·스크린샷), 은행 헤더(컬럼) 설정의 긴 문장. 규칙은 common.ts 맨 위 */
export const depositText = {
  // 거래내역 올리기
  uploadHelp: "은행 앱·인터넷뱅킹의 거래내역조회에서 엑셀로 받아 올려주세요. 토스뱅크는 발급할 때 '입금만 + 엑셀'로 받으면 편합니다. 원본 파일은 저장하지 않습니다.",
  manualColumnsHelp: '은행마다 헤더(컬럼)명이 다릅니다. 파일의 헤더(컬럼)명을 그대로 적거나 목록에서 고르세요. 띄어쓰기는 달라도 됩니다.',
  uploaded: (saved: number, duplicates: number) =>
    `입금 ${saved}건을 올렸습니다.${duplicates ? ` 이미 올린 입금 ${duplicates}건은 건너뛰었습니다.` : ''}`,
  presetNamePrompt: '이 헤더(컬럼) 설정의 이름을 적어 주세요. 예: 신협',
  presetSaved: (name: string) => `"${name}" 헤더(컬럼) 설정을 저장했습니다. 다음부터 목록에서 고르면 됩니다.`,
  columnsFound: (auto: boolean, headerRowNo: number) =>
    `${auto ? '헤더(컬럼)를 자동으로 찾았습니다.' : '지정한 헤더(컬럼)명으로 읽었습니다.'} 헤더는 ${headerRowNo}행입니다.`,
  previewCounts: (deposits: number, skipped: number, duplicates: number) =>
    `입금 ${deposits}건 · 출금·0원·이자 등 건너뛴 줄 ${skipped}개${duplicates ? ` · 이미 올린 입금 ${duplicates}건` : ''}`,
  wrongColumnsHint: '헤더(컬럼)가 틀렸으면 "헤더(컬럼)명 직접 지정"으로 바꿔서 다시 미리보기 하세요.',
  confirmDeleteUpload: '이 업로드의 입금 줄을 지울까요?',

  // 대조 결과
  confirmed: (count: number) => `${count}건 입금 확정했습니다.`,
  confirmHelp: '결과는 "확정"을 눌러야 저장됩니다. 확정하면 입금자명이 그 고객의 별칭으로 저장되어 다음 공구부터 자동 매칭에 쓰입니다.',
  noDeposits: '대조할 입금이 없습니다. 거래내역을 올려주세요.',
  refundCheckHint: '입금하고 취소한 고객이면 누르세요. 주문 목록에 "환불 필요"로 뜹니다.',
  confirmHandled: (name: string, amount: string) =>
    `${name} ${amount} 입금을 "이미 처리 완료된 입금"으로 옮길까요?\n\n대조 결과에서만 빠지고 주문에는 아무 변화가 없습니다. 같은 거래내역을 다시 올려도 다시 나오지 않고 "이미 처리 완료된 입금 보기"에서 되돌릴 수 있습니다.`,
  handledHelp: '대조 결과에서 뺀 입금입니다. 되돌리면 다시 대조 결과에 나옵니다.',

  // 대조 결과 기준 설명 (2026-10-26)
  criteria: [
    { status: 'MATCHED', name: '자동 매칭', desc: '입금자명(또는 저장된 별칭·닉네임)과 금액이 맞고 그런 고객이 1명뿐입니다. [자동 매칭 모두 확정]으로 한 번에 확정할 수 있습니다.' },
    { status: 'CHECK', name: '확인 필요', desc: '같은 고객의 미입금 주문이나 입금이 여러 건입니다. 짝이 바뀌었을 수 있어 [모두 확정]에서 빠지니 하나씩 보고 확정하세요.' },
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

  // 은행 헤더(컬럼) 설정
  defaultsHelp: '거래내역 파일 위쪽 30줄에서 아래 이름의 헤더(컬럼)를 찾습니다. 띄어쓰기는 무시하고 나머지는 정확히 같아야 합니다. 은행 파일의 헤더(컬럼)명이 다르면 자동 인식이 안 되므로 아래에서 은행별로 헤더(컬럼)명을 저장해 두거나 올릴 때 직접 지정하세요.',
  amountOrderNote: '금액 헤더(컬럼)는 앞에서부터 먼저 찾습니다. "거래금액"은 부호가 있는 금액이라 양수만 입금으로 봅니다.',
  typeExcludeNote: '거래 유형 값에 "이자"·"출금"·"지급"이 있으면 뺍니다.',
  aliasHelp: '입금을 확정하면 "입금자명 → 고객"이 저장되고 다음 공구부터 같은 입금자명이면 자동으로 그 고객과 매칭합니다. 잘못 저장된 것은 지우세요.',
  confirmRemove: '삭제할까요?',
}
