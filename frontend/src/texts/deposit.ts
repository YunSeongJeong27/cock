/** 입금 대조(거래내역·스크린샷), 은행 열 설정의 긴 문장. 규칙은 common.ts 맨 위 */
export const depositText = {
  // 거래내역 올리기
  uploadHelp: "은행 앱·인터넷뱅킹의 거래내역조회에서 엑셀로 받아 올려주세요. 토스뱅크는 발급할 때 '입금만 + 엑셀'로 받으면 편합니다. 원본 파일은 저장하지 않습니다.",
  manualColumnsHelp: '은행마다 열 이름이 다릅니다. 파일의 열 이름을 그대로 적거나 목록에서 고르세요. (띄어쓰기는 무시)',
  uploaded: (saved: number, duplicates: number) =>
    `입금 ${saved}건을 올렸습니다.${duplicates ? ` (이미 올린 입금 ${duplicates}건은 건너뜀)` : ''}`,
  presetNamePrompt: '이 열 설정의 이름 (예: 신협, 국민은행)',
  presetSaved: (name: string) => `"${name}" 열 설정을 저장했습니다. 다음부터 목록에서 고르면 됩니다.`,
  columnsFound: (auto: boolean, headerRowNo: number) =>
    `${auto ? '열을 자동으로 찾았습니다.' : '지정한 열 이름으로 읽었습니다.'} (헤더 ${headerRowNo}행)`,
  previewCounts: (deposits: number, skipped: number, duplicates: number) =>
    `입금 ${deposits}건 · 건너뛴 줄 ${skipped}개 (출금·0원·이자 등)${duplicates ? ` · 이미 올린 입금 ${duplicates}건` : ''}`,
  wrongColumnsHint: '열이 틀렸으면 "열 이름 직접 지정"으로 바꿔서 다시 미리보기 하세요.',
  confirmDeleteUpload: '이 업로드의 입금 줄을 지울까요?',

  // 대조 결과
  confirmed: (count: number) => `${count}건 입금 확정했습니다.`,
  confirmHelp: '결과는 "확정"을 눌러야 저장됩니다. 확정하면 입금자명이 그 고객의 별칭으로 저장되어 다음 공구부터 자동 매칭에 쓰입니다.',
  noDeposits: '대조할 입금이 없습니다. 거래내역을 올려주세요.',
  refundCheckHint: '입금하고 취소한 고객이면 누르세요. 주문 목록에 "환불 필요"로 뜹니다.',

  // 스크린샷
  screenshotHelp: '폰에서 엑셀 받기가 번거로우면 은행앱 거래내역 화면을 캡처해서 여러 장 올리세요. AI가 입금자명·금액을 읽고, 직접 확인·수정한 뒤 저장합니다. 읽기 어려운 글자는 [?]로 표시됩니다. 캡처 이미지는 외부 AI 서비스로 전송되며 읽은 직후 삭제됩니다.',
  withdrawalsSkipped: (count: number) => `출금 ${count}건은 뺐습니다.`,
  screenshotSaved: '스크린샷 입금을 저장했습니다. 아래 대조 결과에 반영됩니다.',
  fixUnreadFirst: '[?] 표시를 고친 뒤 저장할 수 있습니다.',
  visionOff: '스크린샷 글자 읽기(비전 AI)가 아직 꺼져 있습니다. 읽은 결과 표에 직접 입력할 수 있습니다.',

  // 은행 열 설정
  defaultsHelp: '거래내역 파일 위쪽 30줄에서 아래 이름의 열을 찾습니다 (띄어쓰기 무시, 정확히 같아야 함). 은행 파일의 열 이름이 다르면 자동 인식이 안 되므로, 아래에서 은행별로 열 이름을 저장해 두거나 올릴 때 직접 지정하세요.',
  amountOrderNote: '(앞에서부터 먼저 찾음. "거래금액"은 부호 있는 금액, 양수만 입금)',
  typeExcludeNote: '(값에 "이자"·"출금"·"지급"이 있으면 제외)',
  aliasHelp: '입금을 확정하면 "입금자명 → 고객"이 저장되고, 다음 공구부터 같은 입금자명이면 자동으로 그 고객과 매칭합니다. 잘못 저장된 것은 지우세요.',
  confirmRemove: '삭제할까요?',
}
