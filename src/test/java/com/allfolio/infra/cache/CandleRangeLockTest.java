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

        assertThat(candleRangeLock.tryLock(tickerKey)).isNotNull();
        assertThat(candleRangeLock.tryLock(tickerKey)).isNull();
    }

    @Test
    void tryLockOnDifferentTickerIsIndependent() {
        String tickerKeyA = "STOCK:005930:" + UUID.randomUUID();
        String tickerKeyB = "STOCK:035420:" + UUID.randomUUID();

        assertThat(candleRangeLock.tryLock(tickerKeyA)).isNotNull();
        assertThat(candleRangeLock.tryLock(tickerKeyB)).isNotNull();
    }

    @Test
    void unlockAllowsAcquiringTheSameLockAgain() {
        String tickerKey = "STOCK:005930:" + UUID.randomUUID();

        String token = candleRangeLock.tryLock(tickerKey);
        assertThat(token).isNotNull();
        candleRangeLock.unlock(tickerKey, token);

        assertThat(candleRangeLock.tryLock(tickerKey)).isNotNull();
    }

    /** 이 프로세스가 실제 획득하지 않은 락(tryLock 호출조차 없었던 키)에 대한 unlock은 아무 효과가 없어야 한다. */
    @Test
    void unlockWithoutPriorTryLockIsNoOp() {
        String tickerKey = "STOCK:005930:" + UUID.randomUUID();

        candleRangeLock.unlock(tickerKey, UUID.randomUUID().toString());

        assertThat(candleRangeLock.tryLock(tickerKey)).isNotNull();
    }

    /**
     * 회귀 테스트(Task 028 code-reviewer M1 실측 수정): TTL 만료로 새 보유자가 같은 키의 락을 새로
     * 잡은 뒤, 원래(이제는 만료된) 보유자가 자기 토큰으로 unlock()을 호출해도 새 보유자의 락을
     * 지우면 안 된다. 예전엔 인-프로세스 {@code ConcurrentHashMap<락키, 토큰>}이 토큰을 덮어써서
     * 이 시나리오에서 새 보유자의 락이 삭제되는 사고가 실제로 재현됐다 — 이제 토큰을 호출자가 직접
     * 들고 있게 해 같은 키라도 호출자별로 독립된 토큰을 참조한다.
     */
    @Test
    void staleOwnerUnlockMustNotDeleteTheNewHolderLock() {
        String tickerKey = "STOCK:005930:" + UUID.randomUUID();

        String originalToken = candleRangeLock.tryLock(tickerKey);
        assertThat(originalToken).isNotNull();
        // TTL 만료를 흉내낸다 — 원래 보유자는 아직 자기 토큰(originalToken)을 들고 있지만, 락 자체는
        // 이미 다른 방식으로 해제됐다고 가정하고 새 보유자가 같은 키를 다시 잡는다.
        candleRangeLock.unlock(tickerKey, originalToken);
        String newToken = candleRangeLock.tryLock(tickerKey);
        assertThat(newToken).isNotNull();

        // 원래(이제는 무효한) 보유자가 자기 토큰으로 뒤늦게 unlock을 호출한다.
        candleRangeLock.unlock(tickerKey, originalToken);

        // 새 보유자의 락은 여전히 유효해야 한다 — 같은 키로 다시 tryLock하면 실패(null)해야 한다.
        assertThat(candleRangeLock.tryLock(tickerKey)).isNull();
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
            Callable<String> task = () -> {
                startLatch.await();
                return candleRangeLock.tryLock(tickerKey);
            };

            // invokeAll()은 모든 태스크가 끝날 때까지 호출 스레드를 블로킹한다 — 각 태스크가 latch를
            // 기다리는 이 시나리오에서 invokeAll() 뒤에 countDown()을 두면 영원히 블로킹되는 교착
            // 상태가 되므로, submit()으로 비동기 제출한 뒤 countDown()해야 한다.
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentRequests; i++) {
                futures.add(executor.submit(task));
            }
            startLatch.countDown();

            AtomicInteger successCount = new AtomicInteger();
            for (Future<String> future : futures) {
                if (future.get(10, TimeUnit.SECONDS) != null) {
                    successCount.incrementAndGet();
                }
            }

            assertThat(successCount.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
