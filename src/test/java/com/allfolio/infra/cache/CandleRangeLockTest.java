package com.allfolio.infra.cache;

import com.allfolio.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers Redis(AbstractIntegrationTest 공유 컨테이너)로 실제 Lua 스크립트(SET NX EX / GET+DEL)를
 * 실행해 티커 단위 캔들 락(cache stampede 방지)을 검증한다(Task 028).
 */
class CandleRangeLockTest extends AbstractIntegrationTest {

    @Autowired
    private CandleRangeLock candleRangeLock;

    @Test
    void secondTryLockOnSameTickerWhileHeldIsRejected() {
        String tickerKey = "STOCK:005930:" + UUID.randomUUID();

        assertThat(candleRangeLock.tryLock(tickerKey)).isTrue();
        assertThat(candleRangeLock.tryLock(tickerKey)).isFalse();
    }

    @Test
    void tryLockOnDifferentTickerIsIndependent() {
        String tickerKeyA = "STOCK:005930:" + UUID.randomUUID();
        String tickerKeyB = "STOCK:035420:" + UUID.randomUUID();

        assertThat(candleRangeLock.tryLock(tickerKeyA)).isTrue();
        assertThat(candleRangeLock.tryLock(tickerKeyB)).isTrue();
    }

    @Test
    void unlockAllowsAcquiringTheSameLockAgain() {
        String tickerKey = "STOCK:005930:" + UUID.randomUUID();

        assertThat(candleRangeLock.tryLock(tickerKey)).isTrue();
        candleRangeLock.unlock(tickerKey);

        assertThat(candleRangeLock.tryLock(tickerKey)).isTrue();
    }

    /** 이 프로세스가 실제 획득하지 않은 락(tryLock 호출조차 없었던 키)에 대한 unlock은 아무 효과가 없어야 한다. */
    @Test
    void unlockWithoutPriorTryLockIsNoOp() {
        String tickerKey = "STOCK:005930:" + UUID.randomUUID();

        candleRangeLock.unlock(tickerKey);

        assertThat(candleRangeLock.tryLock(tickerKey)).isTrue();
    }

    /**
     * 동시 요청 시나리오: N개 스레드가 같은 티커로 동시에 tryLock()을 호출해도 정확히 1개만 성공해야
     * 한다 — 이것이 CandleService(다음 태스크)가 벤더 중복 호출을 1회로 수렴시키는 근거가 되는
     * 락 자체의 상호 배제 보장이다. Virtual Thread(가상 스레드)로 동시성을 재현한다(프로젝트
     * spring.threads.virtual.enabled=true 컨벤션과 일치).
     */
    @Test
    void exactlyOneConcurrentTryLockSucceedsForSameTicker() throws Exception {
        String tickerKey = "STOCK:005930:" + UUID.randomUUID();
        int concurrentRequests = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Callable<Boolean> task = () -> {
                startLatch.await();
                return candleRangeLock.tryLock(tickerKey);
            };

            // invokeAll()은 모든 태스크가 끝날 때까지 호출 스레드를 블로킹한다 — 각 태스크가 latch를
            // 기다리는 이 시나리오에서 invokeAll() 뒤에 countDown()을 두면 영원히 블로킹되는 교착
            // 상태가 되므로, submit()으로 비동기 제출한 뒤 countDown()해야 한다.
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentRequests; i++) {
                futures.add(executor.submit(task));
            }
            startLatch.countDown();

            AtomicInteger successCount = new AtomicInteger();
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    successCount.incrementAndGet();
                }
            }

            assertThat(successCount.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
