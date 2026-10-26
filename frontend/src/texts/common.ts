/**
 * 화면 문구 관리 (2026-10-20 사용자 결정)
 * - 짧은 이름(버튼·제목·칸 이름·상태 표시)은 화면 코드에 그대로 둔다
 * - 긴 문장(안내·설명·경고·확인창·알림창)은 기능별 문구 파일(src/texts/*.ts)에 모은다
 * - 값이 들어가는 문장은 함수로 만든다. **굵게**, [글자](/경로) 는 화면에서 <Rich> 로 그린다
 * - 서버와 화면이 같이 쓰는 문장(상태 이름·재고 알림·멈춤 기본 문구 등)은 서버가 보낸 값을 쓰고 여기 적지 않는다
 * 쓰는 법 (2026-10-26 사용자 결정, docs/design.md "문구 관리 방식")
 * - 처음 쓰는 사람 기준으로 짧고 헷갈리지 않게 쓴다
 * - 쓸데없이 늘어진 괄호 설명은 쓰지 않는다. 의미 전달에 꼭 필요한 짧은 표시는 괄호로 둔다 ("닉네임 (선택)", "단가(원)", "전체 (6)")
 * - 쉼표는 항목을 늘어놓을 때만 쓴다. 문장을 잇는 쉼표는 빼고, 길면 문장을 나눈다
 * - "(으)로"·"이(가)" 대신 josa() 로 받침에 맞는 조사를 붙인다
 */

type Josa = '이/가' | '을/를' | '은/는' | '과/와' | '으로/로'

/** 끝 글자의 받침: 없음 / ㄹ / 그 밖. 숫자는 읽는 소리로 (1 일·7 칠·8 팔은 ㄹ), 영문 등은 받침 없음으로 본다 */
function finalSound(word: string): 'none' | 'rieul' | 'other' {
  const ch = word.trim().slice(-1)
  const code = ch.charCodeAt(0) - 0xac00
  if (code >= 0 && code <= 11171) {
    const jong = code % 28
    return jong === 0 ? 'none' : jong === 8 ? 'rieul' : 'other'
  }
  const digit = '0123456789'.indexOf(ch)
  if (digit >= 0) return (['other', 'rieul', 'none', 'other', 'none', 'none', 'other', 'rieul', 'rieul', 'none'] as const)[digit]
  return 'none'
}

/** 받침에 맞는 조사만 돌려준다: josa('김딸기', '으로/로') → '로', josa('박귤', '이/가') → '이' */
export function josa(word: string, pair: Josa): string {
  const sound = finalSound(word)
  const [withBatchim, withoutBatchim] = pair.split('/')
  if (pair === '으로/로') return sound === 'other' ? withBatchim : withoutBatchim
  return sound === 'none' ? withoutBatchim : withBatchim
}

/** 여러 화면이 같이 쓰는 문장 */
export const commonText = {
  loginRequired: '로그인이 필요합니다.',
  requestFailed: (status: number) => `요청에 실패했습니다. 오류 코드 ${status}`,
  suspendedBanner: '관리자가 사용을 제한한 계정입니다. 조회만 할 수 있습니다. 문의: 관리자',
  expiredBanner: (trial: boolean) =>
    `${trial ? '무료 체험 기간이' : '이용 기간이'} 끝났습니다. 계속 쓰시려면 관리자에게 이용료를 입금해 주세요.`,
}
