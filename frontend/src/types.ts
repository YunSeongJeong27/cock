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
}

export type GroupBuyStatus = 'DRAFT' | 'OPEN' | 'CLOSED'
export type ShippingFeeType = 'NONE' | 'FIXED'
export type ReceiveMethod = 'PICKUP' | 'DELIVERY'
export type PaymentMethod = 'PREPAID' | 'ONSITE'

export interface Product {
  id: number
  name: string
  unitPrice: number
  stockLimit: number | null
  sold: number
  remaining: number | null
  perOrderLimit: number | null
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
  nicknameFieldEnabled: boolean
  unitAddressFieldEnabled: boolean
  kakaoInquiryUrl: string | null
  adminNames: string | null
  createdAt: string
  products: Product[]
  pickupPoints: PickupPoint[]
}

export interface Dashboard {
  orderCount: number
  totalQuantity: number
  totalAmount: number
  prepaidCount: number
  paidCount: number
  unpaidCount: number
  depositReportedCount: number
  onsiteCount: number
  pickedUpCount: number
  refundPendingCount: number
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
}

export type DisplayStatus = 'AWAITING_DEPOSIT' | 'DEPOSIT_REPORTED' | 'PAID' | 'RESERVED' | 'PICKED_UP' | 'CANCELLED'

export interface SellerOrder {
  id: number
  orderNo: number
  source: 'FORM' | 'COMMENT'
  customerKey: string
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
  depositReportedAt: string | null
  paid: boolean
  paidAt: string | null
  paidDepositorName: string | null
  paidAmount: number | null
  onsitePaid: boolean
  pickedUp: boolean
  pickedUpAt: string | null
  refundRequired: boolean
  refundedAt: string | null
  duplicateContact: boolean
  orderedAt: string
  cancelledAt: string | null
  /** 입금 확정 이력 (취소된 확정 포함, 오래된 순) */
  payments: PaymentRecord[]
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
  depositReported: boolean
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
