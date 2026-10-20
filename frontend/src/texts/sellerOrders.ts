/** 판매자 주문 목록·주문 변경·수령 체크의 긴 문장. 규칙은 common.ts 맨 위 */
export const sellerOrderText = {
  paidCannotEdit: '입금 확정된 주문입니다. 바꾸려면 먼저 입금 확정을 취소해 주세요.',

  // 환불
  paidNameDiffers: (paidName: string, depositorName: string | null) =>
    `⚠ 실제 입금된 이름(${paidName})이 주문서 입금자명(${depositorName ?? ''})과 다릅니다.`,
  refundSendCheck: (paidName: string | null, depositorName: string | null) =>
    `보낼 때 은행 앱에 뜨는 받는 분 이름이 ${paidName ? `실제 입금된 이름(${paidName})` : `입금자명(${depositorName ?? ''})`}과 같은지 확인하세요. 다르면 보내지 말고 고객에게 확인하세요.`,
  refundBeforeConfirm: '아직 주문 확정 전입니다. 통장에서 입금을 확인한 뒤 처리해 주세요.',
  confirmApproveRefund: (orderNo: number) =>
    `${orderNo}번 주문을 취소하고 "환불 필요"로 표시할까요? 위 계좌로 직접 돈을 보낸 뒤 [환불 완료]를 눌러 주세요.`,
  confirmRejectRefund: '환불 신청을 받지 않을까요? 주문은 그대로 남고, 고객 화면에 "환불 신청이 반려됐어요"가 보입니다.',
  refundRequestsBanner: (count: number) =>
    `고객 환불 신청이 ${count}건 있습니다. 상세에서 [취소하고 환불] 또는 [취소 안 함]을 눌러 주세요.`,

  // 주문 처리
  changedReplyHead: '**변경했습니다.** 댓글 답글 예시 (고쳐서 복사해 쓰세요)',
  confirmUnconfirmPaid: '입금 확정을 취소할까요?',
  manualAmountInvalid: '입금액은 숫자로 입력해 주세요. (예: 3800)',
  confirmCancel: (orderNo: number, paid: boolean) =>
    `${orderNo}번 주문을 취소할까요?${paid ? ' (입금 확정된 주문이라 환불 필요로 표시됩니다)' : ''}`,
  sameNicknameBanner: (count: number) =>
    `⚠ 링크 주문과 댓글 주문의 닉네임이 같은 주문이 ${count}건 있습니다. 같은 사람이 두 번 주문한 것이면 하나를 취소하거나 바꿔 주세요.`,

  // 완료 주문 삭제
  confirmDelete: (count: number) => `선택한 ${count}건을 삭제할까요? 되돌릴 수 없습니다.`,
  deleteNote: '완료된 주문만 지울 수 있고, 지우면 되돌릴 수 없습니다.',

  // 주문 변경
  forcedBySettings: (changes: string[]) => `공구 설정이 바뀌어서, 저장하면 ${changes.join(', ')}(으)로 바뀝니다.`,
  editNote: '휴대폰 번호는 바꿀 수 없습니다(바꾸려면 주문 취소 후 다시 주문). 고객 화면에는 "판매자가 주문을 변경했어요"가 표시됩니다.',
  editNoteComment: ' 댓글 집계를 다시 반영해도 바꾼 내용은 그대로 남습니다.',

  // 수령 체크
  confirmPickupUnpaid: (name: string | null) => `${name ?? ''}님은 아직 입금 확인이 안 됐습니다. 그래도 수령 처리할까요?`,
}
