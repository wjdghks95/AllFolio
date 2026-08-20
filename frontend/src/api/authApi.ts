// 인증 API 클라이언트. 에러는 code만 담아 던지고 문구는 만들지 않는다 —
// 문구 조회(messageForErrorCode)는 이 API를 호출하는 화면 컴포넌트의 책임이다.
import type { SignupRequest, LoginRequest, TokenResponse, ErrorResponse } from './types'

export class ApiError extends Error {
  code: string
  constructor(code: string, message?: string) {
    super(message ?? code)
    this.code = code
  }
}

async function postAuth<TReq, TRes>(path: string, body: TReq): Promise<TRes> {
  let res: Response
  try {
    res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    // 'NETWORK_ERROR'는 ErrorCode(백엔드가 실제로 내려주는 코드 집합)에 없는 클라이언트 전용
    // 센티널이다. messageForErrorCode가 모르는 코드는 폴백 문구로 처리하므로 별도 분기 없이 던진다.
    throw new ApiError('NETWORK_ERROR')
  }
  if (!res.ok) {
    const err: ErrorResponse = await res.json()
    throw new ApiError(err.code, err.message)
  }
  return res.json() as Promise<TRes>
}

export function signup(req: SignupRequest): Promise<TokenResponse> {
  return postAuth<SignupRequest, TokenResponse>('/v1/auth/signup', req)
}

export function login(req: LoginRequest): Promise<TokenResponse> {
  return postAuth<LoginRequest, TokenResponse>('/v1/auth/login', req)
}
