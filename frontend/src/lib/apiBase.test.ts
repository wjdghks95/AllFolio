import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiUrl } from './apiBase'

describe('apiUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('VITE_API_BASE_URL이 빈 문자열이면(dev 모드) 경로를 그대로 반환한다', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    expect(apiUrl('/v1/auth/login')).toBe('/v1/auth/login')
  })

  it('VITE_API_BASE_URL 자체가 없으면(undefined) 경로를 그대로 반환한다', () => {
    vi.stubEnv('VITE_API_BASE_URL', undefined)
    expect(apiUrl('/v1/auth/login')).toBe('/v1/auth/login')
  })

  it('VITE_API_BASE_URL이 있으면 앞에 붙여 절대 URL을 만든다', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.allfolio.com')
    expect(apiUrl('/v1/auth/login')).toBe('https://api.allfolio.com/v1/auth/login')
  })
})
