package akka.http.caching.javadsl;

import java.util.concurrent.CompletionStage;

public interface LoadingCache<K, V> extends Cache<K, V> {
  CompletionStage<V> loadFuture(K key);
  CompletionStage<java.util.Map<K, V>> loadAllMap(java.util.Set<K> keys);
}
