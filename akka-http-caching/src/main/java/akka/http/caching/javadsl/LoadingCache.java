/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */


package akka.http.caching.javadsl;

import java.util.concurrent.CompletionStage;

public interface LoadingCache<K, V> extends Cache<K, V> {

  /**
   * Returns either the cached value at `K` or attempts to load it on it's first read.
   * Should time t be refreshTime < t < expireTime, then returns the previous value and triggers a refresh.
   * Should time t be refreshTime <= expireTime < t, then triggers the loading function and returns that value.
   */
  CompletionStage<V> loadFuture(K key);

  /**
   * Returns a Map of all values or fails.
   */
  CompletionStage<java.util.Map<K, V>> loadAllMap(java.util.Set<K> keys);
}
