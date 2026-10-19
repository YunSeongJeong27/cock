export type Role = 'SELLER' | 'ADMIN'
export type Plan = 'TRIAL' | 'PAID'

export interface Me {
  id: number
  email: string
  displayName: string
  role: Role
  plan: Plan
  trialEndsAt: string
  paidUntil: string | null
  expired: boolean
  suspended: boolean
  defaultBankName: string | null
  defaultAccountNumber: string | null
  defaultAccountHolder: string | null
  /** 댓글 주문 기능을 쓸 수 있는지 (관리자가 전체 공개했거나 이 판매자에게 허용) */
  commentFeatureEnabled: boolean
  /** 알림 켜짐/꺼짐 (2026-10-19, 모든 종류가 옴) */
  notifications: Record<NotificationType, boolean>
}

/** 판매자 알림 종류. MAIL_ = 가입 이메일로 보내는 메일, SCREEN_ = 판매자 화면의 알림 칸 */
export type NotificationType =
  | 'MAIL_STOCK' | 'MAIL_REFUND_REQUEST' | 'MAIL_NEW_ORDER' | 'MAIL_CUSTOMER_CHANGE' | 'MAIL_CUSTOMER_CANCEL' | 'MAIL_AUTO_CLOSE'
  | 'SCREEN_STOCK' | 'SCREEN_REFUND_REQUEST' | 'SCREEN_SAME_NICKNAME'

export type GroupBuyStatus = 'DRAFT' | 'OPEN' | 'CLOSED'
export type ShippingFeeType = 'NONE' | 'FIXED'
export type ReceiveMethod = 'PICKUP' | 'DELIVERY'
export type PaymentMethod = 'PREPAID' | 'ONSITE'
/** 주문 받는 방법: 주문 링크만(닉네임 선택) / 주문 링크 + 댓글(닉네임 필수, 댓글 집계 사용) */
export type OrderChannel = 'LINK_ONLY' | 'LINK_AND_COMMENT'

export interface Product {
  id: number
  name: string
  unitPrice: number
  stockLimit: number | null
  sold: number
  /** 판매자용 남은 수량. 재고보다 더 주문되면 음수 (예: -3 = 3개 초과). 재고 제한이 없으면 null */
  remaining: number | null
  perOrderLimit: number | null
  /** 남은 수량이 이 값 이하가 되면 알림 (화면·메일). 없으면 null */
  stockAlertThreshold: number | null
  /** 켜면 준비 수량에 도달했을 때 품절로 주문을 막음 (기본 꺼짐) */
  blockWhenSoldOut: boolean
  /** 품절로 막는 중 */
  soldOut: boolean
}

export interface PickupPoint {
  id: number
  name: string
  info: string | null
}

export interface GroupBuy {
  id: number
  title: string
  description: string | null
  publicToken: string
  status: GroupBuyStatus
  acceptingOrders: boolean
  closeAt: string | null
  bankName: string | null
  accountNumber: string | null
  accountHolder: string | null
  pickupEnabled: boolean
  pickupPrepayEnabled: boolean
  pickupOnsiteEnabled: boolean
  deliveryEnabled: boolean
  shippingFeeType: ShippingFeeType
  shippingFee: number
  orderChannel: OrderChannel
  unitAddressFieldEnabled: boolean
  kakaoInquiryUrl: string | null
  adminNames: string | null
  createdAt: string
  products: Product[]
  pickupPoints: PickupPoint[]
  /** 주문 잠시 멈춤 중 (고객의 주문·수정·취소·환불 신청이 막힘) */
  paused: boolean
  /** 판매자가 적은 멈춤 문구 (null 이면 기본 문구) */
  pauseMessage: string | null
  pauseLinkUrl: string | null
}

/** 주문 잠시 멈춤 기본 문구 (서버 GroupBuy.DEFAULT_PAUSE_MESSAGE 와 같음) */
export const DEFAULT_PAUSE_MESSAGE = '현재 주문 집계 중입니다. 판매자에게 문의해 주시면 감사하겠습니다.'

export interface Dashboard {
  orderCount: number
  totalQuantity: number
  totalAmount: number
  prepaidCount: number
  paidCount: number
  unpaidCount: number
  onsiteCount: number
  pickedUpCount: number
  refundPendingCount: number
  /** 판매자 확인을 기다리는 고객 환불 신청 */
  refundRequestCount: number
  cancelledCount: number
}

export interface GroupBuySummary {
  id: number
  title: string
  status: GroupBuyStatus
  acceptingOrders: boolean
  closeAt: string | null
  publicToken: string
  createdAt: string
  dashboard: Dashboard
  paused: boolean
}

export type DisplayStatus = 'AWAITING_DEPOSIT' | 'PAID' | 'RESERVED' | 'PICKED_UP' | 'CANCELLED'

export interface SellerOrder {
  id: number
  orderNo: number
  source: 'FORM' | 'COMMENT'
  customerId: number
  phone: string | null
  depositorName: string | null
  nickname: string | null
  unitAddress: string | null
  receiveMethod: ReceiveMethod
  pickupPointId: number | null
  pickupPointName: string | null
  recipientName: string | null
  recipientPhone: string | null
  zipcode: string | null
  address: string | null
  addressDetail: string | null
  paymentMethod: PaymentMethod
  items: { productId: number; productName: string; quantity: number; unitPrice: number }[]
  totalQuantity: number
  itemsAmount: number
  shippingFee: number
  expectedAmount: number
  status: DisplayStatus
  statusLabel: string
  active: boolean
  paid: boolean
  paidAt: string | null
  paidDepositorName: string | null
  paidAmount: number | null
  onsitePaid: boolean
  pickedUp: boolean
  pickedUpAt: string | null
  refundRequired: boolean
  refundedAt: string | null
  /** 고객의 환불 신청 (반려 시각이 없고 주문이 살아 있으면 판매자 확인 대기) */
  refundRequestedAt: string | null
  refundRejectedAt: string | null
  /** 환불받을 계좌 (계좌번호는 서버에 암호화 저장, 환불 완료·반려 때 지움) */
  refundBank: string | null
  refundAccount: string | null
  refundHolder: string | null
  duplicateContact: boolean
  orderedAt: string
  /** 마지막으로 바뀐 시각 (입금 신고·확정·수령·취소·판매자 변경 등) */
  updatedAt: string
  cancelledAt: string | null
  /** 입금 확정 이력 (취소된 확정 포함, 오래된 순) */
  payments: PaymentRecord[]
  /** 주문을 바꾼 기록 (오래된 순). CUSTOMER = 고객이 같은 휴대폰 번호로 다시 주문해서 바뀜 */
  changes: { summary: string; changedBy: 'SELLER' | 'CUSTOMER'; changedAt: string }[]
  /** 닉네임이 같은 다른 경로(링크↔댓글) 주문 번호. 같은 사람이 양쪽으로 주문했을 수 있음 (경고만) */
  sameNicknameOrderNos: number[]
}

export interface ColumnMapping {
  dateColumn: string | null
  nameColumn: string | null
  amountColumn: string | null
  typeColumn: string | null
}

export interface DepositLine {
  rowNo: number
  name: string
  amount: number
  dateText: string | null
  depositedAt: string | null
}

export interface ParsedStatement {
  mapping: ColumnMapping
  autoDetected: boolean
  headerRowNo: number
  headers: string[]
  columnOptions: string[]
  preview: string[][]
  deposits: DepositLine[]
  skippedRows: number
  message: string | null
}

export interface OrderBrief {
  orderId: number
  orderNo: number
  depositorName: string | null
  nickname: string | null
  phoneLast4: string
  expectedAmount: number
  orderedAt: string
}

export type MatchStatus = 'MATCHED' | 'SUGGESTED' | 'AMBIGUOUS' | 'UNRELATED'

export interface MatchRow {
  rowId: number
  rowNo: number
  depositorName: string
  amount: number
  datetimeText: string | null
  status: MatchStatus
  statusLabel: string
  order: OrderBrief | null
  evidence: string | null
  amountDiff: number
  candidates: { order: OrderBrief; evidence: string; amountDiff: number }[]
  /** 관련 없는 입금의 확인 안내 (중복 입금·환불 가능성) */
  note: string | null
}

export interface MatchResponse {
  rows: MatchRow[]
  unpaid: OrderBrief[]
  /** 판매자가 직접 고를 수 있는 모든 미입금 선입금 주문 (다른 입금에 추천된 주문 포함) */
  openOrders: OrderBrief[]
  used: { rowId: number; depositorName: string; amount: number; datetimeText: string | null; orderId: number; orderNo: number }[]
  matchedCount: number
  suggestedCount: number
  ambiguousCount: number
  unrelatedCount: number
}

export interface Preset {
  id: number
  name: string
  dateColumn: string | null
  nameColumn: string
  amountColumn: string
  typeColumn: string | null
}

export interface UploadSummary {
  id: number
  source: 'EXCEL' | 'CSV' | 'SCREENSHOT'
  fileName: string | null
  uploadedAt: string
  rowCount: number
  usedCount: number
}

/** 입금 확정 1건. manual = 거래내역 없이 수동 확정, cancelledAt 이 있으면 취소된 확정 */
export interface PaymentRecord {
  depositorName: string
  amount: number
  manual: boolean
  confirmedAt: string
  cancelledAt: string | null
}
