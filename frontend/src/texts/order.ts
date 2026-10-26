import { josa } from './common'

/** 고객 주문 화면(주문서 링크)의 긴 문장. 규칙은 common.ts 맨 위 */
export const orderText = {
  postcodeLoadFailed: '주소 검색을 불러오지 못했습니다. 주소를 직접 입력해 주세요.',
  refundRequestedAlert: '환불 신청을 했습니다. 판매자가 확인한 뒤 주문을 취소하고 환불합니다.',
  cancelledAlert: '주문을 취소했습니다.',
  changeNote: '주문 확정 전에는 **주문 수정**으로 바꿀 수 있습니다. 확정 후에는 **환불 신청** 후 다시 주문해 주세요.',
  pausedNote: '지금은 주문·주문 수정·취소·환불 신청을 잠시 받지 않습니다. 내 주문 보기는 됩니다.',

  // 주문 완료
  sendTo: (amount: string) => `아래 계좌로 **${amount}**을 보내주세요.`,
  depositorReminder: (name: string) => `받는 분 통장 표시를 꼭 **${name}**${josa(name, '으로/로')} 해주세요. 입금 확인이 빨라집니다.`,
  confirmAfterDeposit: '판매자가 입금을 확인하면 "주문 확정"으로 바뀝니다.',
  payOnsite: '현장에서 결제해 주세요.',
  checkLater: '"내 주문"에서 언제든 상태를 확인할 수 있습니다.',

  // 내 주문
  changedBySeller: (time: string) => `판매자가 ${time}에 주문을 변경했어요. 위 내용·금액을 확인해 주세요.`,
  refundPlanned: '취소됨 · 판매자가 환불할 예정입니다.',
  refundRequested: '환불 신청됨 · 판매자가 확인 중입니다.',
  refundRejected: '환불 신청이 취소됐어요. 판매자에게 문의해 주세요.',
  editableUntilConfirm: '주문 확정 전까지 수정할 수 있어요.',
  confirmedCannotEdit: '확정된 주문은 수정할 수 없어요. 변경은 환불 신청 후 다시 주문해 주세요.',

  // 내 주문 찾기
  identifyHint: '주문할 때 적은 휴대폰 번호 전체를 입력해 주세요.',
  identifyNotFound: '주문서 링크로 주문하지 않은 분은 여기서 조회되지 않습니다. 판매자에게 문의해 주세요.',

  // 주문서
  editLockedFields: '휴대폰 번호·입금자명은 수정할 수 없어요. 바꾸려면 판매자에게 문의해 주세요.',
  useEditButton: (prepaid: boolean) => prepaid
    ? '주문 확정 전까지 위 **주문 수정**을 눌러 고칠 수 있어요.'
    : '주문을 고치려면 위 **주문 수정**을 눌러 주세요.',
  otherDevicePrefill: '개인정보 보호를 위해 다른 기기에서 입력한 휴대폰 번호·주소는 채우지 않습니다. 다시 입력해 주세요.',
  depositorHintOnsite: '주문 확인용 이름입니다.',
  depositorHintTransfer: '계좌이체한 입금자명과 반드시 같아야 합니다.',
  refundThenReorder: '입금하신 경우에는 환불 후 재주문 하셔야 합니다.',
  consent: '주문하면 [개인정보처리방침](/privacy)에 따라 주문 처리에 필요한 연락처·입금자명·배송지를 판매자에게 전달하는 데 동의하는 것으로 봅니다.',

  // 취소·환불 신청
  pausedNoCancel: '판매자가 주문을 잠시 멈춰서 지금은 취소·환불 신청을 할 수 없습니다.',
  cancelIfNotPaid: '입금을 안 하셨다면 바로 주문 취소할 수 있습니다.',
  cancelBeforeConfirm: '주문 확정 전에는 바로 취소할 수 있습니다. ',
  cancelClosed: '주문이 마감되어 바로 취소는 할 수 없습니다. 판매자에게 문의해 주세요. ',
  refundIfPaid: '입금하셨다면 환불 신청을 해 주세요. 판매자가 확인한 뒤 취소·환불합니다.',
  cancelFormNote: '입금하셨다면 **환불 신청**을 눌러 주세요. 판매자가 확인한 뒤 환불합니다.',
  refundFormNote: '판매자가 확인한 뒤 주문을 취소하고 아래 계좌로 환불합니다. 계좌는 환불에만 쓰고 환불이 끝나면 지웁니다.',
  chooseOrder: (refund: boolean) => `${refund ? '환불 신청할' : '취소할'} 주문을 골라주세요.`,
}
