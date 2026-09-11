import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Node의 실험적 내장 localStorage(--localstorage-file 미지정 시 setItem 등이 없는 빈 stub)가
// happy-dom의 window.localStorage보다 먼저 globalThis를 점유하며, happy-dom도 이 stub을
// 그대로 반환해 테스트 환경에서 localStorage 전체가 무력화된다(Node 20.13+/22+에서 실측 확인).
// 메모리 기반 최소 Storage 구현으로 명시적으로 교체한다.
if (typeof globalThis.localStorage?.setItem !== 'function') {
  class MemoryStorage implements Storage {
    private store = new Map<string, string>()
    get length(): number {
      return this.store.size
    }
    clear(): void {
      this.store.clear()
    }
    getItem(key: string): string | null {
      return this.store.has(key) ? this.store.get(key)! : null
    }
    key(index: number): string | null {
      return Array.from(this.store.keys())[index] ?? null
    }
    removeItem(key: string): void {
      this.store.delete(key)
    }
    setItem(key: string, value: string): void {
      this.store.set(key, String(value))
    }
  }

  Object.defineProperty(globalThis, 'localStorage', {
    value: new MemoryStorage(),
    configurable: true,
    writable: true,
  })
}

afterEach(cleanup)
