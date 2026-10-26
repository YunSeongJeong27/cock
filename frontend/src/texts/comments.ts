/** 댓글 집계 탭의 긴 문장. 규칙은 common.ts 맨 위 */
export const commentText = {
  confirmApply: (newCount: number, sameNickname: number) =>
    `새 손님 ${newCount}명을 주문으로 추가합니다. 이미 반영된 손님의 주문은 그대로 둡니다. 계속할까요?`
    + (sameNickname > 0
      ? `\n\n⚠ 그중 링크 주문과 닉네임이 같은 손님이 ${sameNickname}명 있습니다. 같은 사람이면 주문이 두 번 잡히니 반영 후 주문 목록에서 확인해 주세요.`
      : ''),
  applied: (created: number, review: number) =>
    `새 댓글 주문 ${created}건을 만들었습니다.`
    + (review > 0 ? ` 이미 반영된 주문과 댓글이 다른 ${review}건은 표의 ⚠ 표시를 보고 주문 목록에서 직접 바꾸거나 취소해 주세요.` : ''),
  appliedDifferent: (orderNo: number | null, orderQuantity: number | null) =>
    `⚠ 주문 ${orderNo}번은 ${orderQuantity}개라 댓글 합계와 다릅니다. 직접 확인해 주세요.`,
  multiProductBlocked: '상품이 여러 개인 공구는 아직 댓글 주문을 받을 수 없습니다. 댓글을 상품별로 나눠 세는 기능을 준비 중입니다. **이 공구는 주문 링크로 주문을 받아 주세요.**',
  previousApplied: (count: number) => `이전에 반영한 댓글 주문 ${count}건은 주문 목록에 그대로 있습니다.`,
  uploadHelp: '밴드 댓글 화면을 여러 장 캡처해 올리거나 댓글 텍스트를 붙여넣으세요. 공지·광고도 일단 읽고 공구 설정의 관리자 닉네임과 주문 아닌 댓글은 집계에서 뺍니다. 캡처는 외부 AI로 전송되고 읽은 직후 삭제됩니다.',
  pasteLabel: '또는 텍스트 붙여넣기',
  pasteHint: '한 줄에 하나씩 "닉네임 | 댓글" 또는 "닉네임: 댓글" 또는 탭으로 구분해서 붙여넣으세요.',
  alreadySavedSkipped: (count: number) =>
    `이미 저장된 댓글과 똑같은 ${count}개는 뺐습니다. 같은 손님이 같은 댓글을 또 단 거라면 다시 넣으세요.`,
  reviewHelp: '작성자·내용을 고치거나 지울 수 있습니다. 노란 줄은 캡처가 겹쳐서 생긴 중복으로 의심되는 댓글입니다. 읽지 못한 글자는 [?]로 보이며 고친 뒤 저장할 수 있습니다.',
  reapplyHelp: '캡처를 여러 번 올려도 됩니다. 반영하면 처음 보는 손님만 주문으로 추가하고 이미 반영된 손님의 주문은 바꾸지 않습니다.',
  sameAsLinkOrder: (orderNos: number[]) => `⚠ 링크 주문 ${orderNos.join(', ')}번과 닉네임이 같습니다. 같은 사람이면 중복입니다.`,
  staleOrders: '**집계에 없는 댓글 주문**: 댓글로 취소했거나 댓글을 지운 손님입니다. 자동으로 취소하지 않으니 주문 목록에서 확인해 주세요.',
  reviewExcluded: (count: number) => `확인 필요 ${count}개는 수량을 정하기 전까지 합계에서 빠집니다`,
  confirmDeleteAll: '저장한 댓글을 모두 지울까요?',
}
