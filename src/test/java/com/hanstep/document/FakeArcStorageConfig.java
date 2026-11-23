package com.hanstep.document;

import com.hanstep.document.storage.ArcImageStorage;
import com.hanstep.document.storage.ArcStorageException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * R2 대신 메모리에 두는 등록증 저장소. 테스트가 실제 버킷에 올리지 않게 한다. @TestConfiguration 이라 컴포넌트 스캔에서 빠지고,
 * 쓰는 테스트만 @Import 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeArcStorageConfig {

    @Bean
    @Primary
    FakeArcImageStorage fakeArcImageStorage() {
        return new FakeArcImageStorage();
    }

    public static class FakeArcImageStorage implements ArcImageStorage {

        public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        public final AtomicInteger puts = new AtomicInteger();
        public final AtomicInteger deletes = new AtomicInteger();
        public volatile boolean failPut;
        /** 객체는 써 놓고 실패를 돌려준다(응답 전에 연결이 끊긴 경우). */
        public volatile boolean failAfterStore;
        public volatile boolean failDelete;
        /** 이 키만 삭제를 실패시킨다. */
        public final Set<String> failDeleteKeys = ConcurrentHashMap.newKeySet();
        /** 삭제 직전에 부른다(삭제 도중 다른 요청이 겹치게 할 때). */
        public volatile Consumer<String> beforeDelete;

        @Override
        public void put(String objectKey, byte[] bytes, String contentType) {
            puts.incrementAndGet();
            if (failPut) {
                throw new ArcStorageException("fake put failure", null);
            }
            objects.put(objectKey, bytes.clone());
            if (failAfterStore) {
                throw new ArcStorageException("fake put failure after store", null);
            }
        }

        @Override
        public void delete(String objectKey) {
            deletes.incrementAndGet();
            Consumer<String> hook = beforeDelete;
            if (hook != null) {
                hook.accept(objectKey);
            }
            if (failDelete || failDeleteKeys.contains(objectKey)) {
                throw new ArcStorageException("fake delete failure", null);
            }
            objects.remove(objectKey);
        }

        public void reset() {
            objects.clear();
            puts.set(0);
            deletes.set(0);
            failPut = false;
            failAfterStore = false;
            failDelete = false;
            failDeleteKeys.clear();
            beforeDelete = null;
        }
    }
}
