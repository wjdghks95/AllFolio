// authApi 전용 테스트. postAuth의 non-JSON 에러 응답 방어 코드만 검증한다
// (나머지 authApi 동작은 지금까지 화면 테스트의 fetch 모킹으로 간접 검증돼 왔다 — Task 018 결정).
import { afterEach, describe, expect, it, vi } from 'vitest'
import { login } from './authApi'

// WAS 500 에러 페이지, 인프라 502 등 본문이 JSON이 아닌 응답을 흉내낸다.
function nonJsonResponse(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async (): Promise<unknown> => {
      throw new SyntaxError('Unexpected token < in JSON at position 0')
    },
  } as Response
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('non-JSON 에러 응답 방어', () => {
  it('에러 응답 본문이 JSON이 아니면 상태 코드 기반 ApiError(UNKNOWN_ERROR)로 변환한다', async () => {
    const fetchMock = vi.fn(async () => nonJsonResponse(500))
    vi.stubGlobal('fetch', fetchMock)

    await expect(login({ email: 'a@a.com', password: 'password1' })).rejects.toMatchObject({
      code: 'UNKNOWN_ERROR',
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
