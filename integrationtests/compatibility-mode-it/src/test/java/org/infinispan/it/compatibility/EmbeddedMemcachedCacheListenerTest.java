package org.infinispan.it.compatibility;

import net.spy.memcached.CachedData;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.transcoders.SerializingTranscoder;
import net.spy.memcached.transcoders.Transcoder;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.VersionedValue;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.marshall.AbstractMarshaller;
import org.infinispan.commons.util.concurrent.NotifyingFuture;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryVisited;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryModifiedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryRemovedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryVisitedEvent;
import org.infinispan.test.AbstractInfinispanTest;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.jgroups.util.Util.assertTrue;
import static org.testng.AssertJUnit.assertEquals;

/**
 * Test cache listeners bound to embedded cache and operation over Memcached cache.
 *
 * @author Jiri Holusa [jholusa@redhat.com]
 */
@Test(groups = "functional", testName = "it.compatibility.EmbeddedMemcachedCacheListenerTest")
public class EmbeddedMemcachedCacheListenerTest extends AbstractInfinispanTest {

   CompatibilityCacheFactory<String, String> cacheFactory;

   @BeforeMethod
   protected void setup() throws Exception {
      cacheFactory = new CompatibilityCacheFactory<String, String>(
            "memcachedCache", new SpyMemcachedCompatibleMarshaller(), CacheMode.LOCAL).setup();
   }

   @AfterMethod
   protected void teardown() {
      CompatibilityCacheFactory.killCacheFactories(cacheFactory);
   }

   public void testLoadingAndStoringEventsMemcached() throws InterruptedException, ExecutionException, TimeoutException {
      Cache<String, String> embedded = cacheFactory.getEmbeddedCache();
      MemcachedClient remote = cacheFactory.getMemcachedClient();

      TestCacheListener l = new TestCacheListener();
      embedded.addListener(l);

      assertTrue(l.created.isEmpty());
      assertTrue(l.removed.isEmpty());
      assertTrue(l.modified.isEmpty());
      assertTrue(l.visited.isEmpty());

      Future<Boolean> future1 = remote.add("k", 0, "v");
      assertTrue(future1.get(60, TimeUnit.SECONDS));

      assertEquals(1, l.createdCounter);
      assertEquals("v", l.created.get("k"));
      assertTrue(l.removed.isEmpty());
      assertEquals(1, l.modifiedCounter);
      assertEquals("v", l.modified.get("k"));
      assertTrue(l.visited.isEmpty());

      Future<Boolean> future2 = remote.set("key", 0, "value");
      assertTrue(future2.get(60, TimeUnit.SECONDS));

      assertEquals(2, l.createdCounter);
      assertTrue(l.removed.isEmpty());
      assertEquals(2, l.modifiedCounter);
      assertTrue(l.visited.isEmpty());

      Future<Boolean> future3 = remote.set("key", 0, "modifiedValue");
      assertTrue(future3.get(60, TimeUnit.SECONDS));

      assertEquals(2, l.createdCounter);
      assertTrue(l.removed.isEmpty());
      assertEquals(3, l.modifiedCounter);
      assertEquals("modifiedValue", l.modified.get("key"));
      assertTrue(l.visited.isEmpty());

      Future<Boolean> future4 = remote.replace("k", 0, "replacedValue");
      assertTrue(future4.get(60, TimeUnit.SECONDS));

      assertEquals(2, l.createdCounter);
      assertTrue(l.removed.isEmpty());
      assertEquals(4, l.modifiedCounter);
      assertEquals("replacedValue", l.modified.get("k"));
      assertTrue(l.visited.isEmpty());

      //resetting so don't have to type "== 2" etc. all over again
      l.reset();

      Future<Boolean> future5 = remote.delete("key");
      assertTrue(future5.get(60, TimeUnit.SECONDS));

      assertTrue(l.created.isEmpty());
      assertEquals(1, l.removedCounter);
      assertEquals("modifiedValue", l.removed.get("key"));
      assertTrue(l.modified.isEmpty());

      l.reset();

      String value = (String) remote.get("k");
      assertTrue(l.created.isEmpty());
      assertTrue(l.removed.isEmpty());
      assertTrue(l.modified.isEmpty());
      assertEquals(1, l.visitedCounter);
      assertEquals("replacedValue", l.visited.get("k"));
      assertEquals("replacedValue", value);

      l.reset();
   }

   private class SpyMemcachedCompatibleMarshaller extends AbstractMarshaller {

      private final Transcoder<Object> transcoder = new SerializingTranscoder();

      @Override
      protected ByteBuffer objectToBuffer(Object o, int estimatedSize) {
         CachedData encoded = transcoder.encode(o);
         return new ByteBuffer(encoded.getData(), 0, encoded.getData().length);
      }

      @Override
      public Object objectFromByteBuffer(byte[] buf, int offset, int length) {
         return transcoder.decode(new CachedData(0, buf, length));
      }

      @Override
      public boolean isMarshallable(Object o) throws Exception {
         try {
            transcoder.encode(o);
            return true;
         } catch (Throwable t) {
            return false;
         }
      }
   }
}