/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.cache.ehcache

import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Provider

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.ehcache.config.builders.CacheConfigurationBuilder
import org.ehcache.config.builders.ResourcePoolsBuilder
import org.ehcache.CacheManager
import play.api.cache.ehcache.EhCacheApi.EhExpirableCacheValue
import play.api.cache.AsyncCacheApi
import play.api.cache.SyncCacheApi
import play.api.inject._
import play.api.test.PlaySpecification
import play.api.test.WithApplication
import play.cache.NamedCache

class EhCacheApiSpec extends PlaySpecification {
  sequential

  "SyncCacheApi" should {
    "bind named caches" in new WithApplication(
      _.configure(
        "play.cache.bindCaches" -> Seq("custom")
      )
    ) {
      override def running() = {
        val controller = app.injector.instanceOf[NamedCacheController]
        val syncCacheName =
          controller.cache.asInstanceOf[SyncEhCacheApi].cache
        val asyncCacheName =
          controller.asyncCache.asInstanceOf[EhCacheApi].cache

        // no way to get cache name anymore, so doing most basic test
        syncCacheName.getClass.getSimpleName must_== "Ehcache"
        asyncCacheName.getClass.getSimpleName must_== "Ehcache"
      }
    }
    "bind already created named caches" in new WithApplication(
      _.overrides(
        bind[CacheManager].toProvider[CustomCacheManagerProvider]
      ).configure(
        "play.cache.createBoundCaches" -> false,
        "play.cache.bindCaches"        -> Seq("custom")
      )
    ) {
      override def running() = {
        app.injector.instanceOf[NamedCacheController]
      }
    }
    "get values from cache" in new WithApplication() {
      override def running() = {
        val cacheApi     = app.injector.instanceOf[AsyncCacheApi]
        val syncCacheApi = app.injector.instanceOf[SyncCacheApi]
        syncCacheApi.set("foo", "bar")
        Await.result(cacheApi.getOrElseUpdate[String]("foo")(Future.successful("baz")), 1.second) must_== "bar"
        syncCacheApi.getOrElseUpdate("foo")("baz") must_== "bar"
      }
    }

    "get values from cache without deadlocking" in new WithApplication(
      _.overrides(
        bind[ExecutionContext].toInstance(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1)))
      )
    ) {
      override def running() = {
        val syncCacheApi = app.injector.instanceOf[SyncCacheApi]
        syncCacheApi.set("foo", "bar")
        syncCacheApi.getOrElseUpdate[String]("foo")("baz") must_== "bar"
      }
    }

    "remove values from cache" in new WithApplication() {
      override def running() = {
        val cacheApi     = app.injector.instanceOf[AsyncCacheApi]
        val syncCacheApi = app.injector.instanceOf[SyncCacheApi]
        syncCacheApi.set("foo", "bar")
        Await.result(cacheApi.getOrElseUpdate[String]("foo")(Future.successful("baz")), 1.second) must_== "bar"
        syncCacheApi.remove("foo")
        Await.result(cacheApi.get[String]("foo"), 1.second) must beNone
      }
    }

    "remove all values from cache" in new WithApplication() {
      override def running() = {
        val cacheApi     = app.injector.instanceOf[AsyncCacheApi]
        val syncCacheApi = app.injector.instanceOf[SyncCacheApi]
        syncCacheApi.set("foo", "bar")
        Await.result(cacheApi.getOrElseUpdate[String]("foo")(Future.successful("baz")), 1.second) must_== "bar"
        Await.result(cacheApi.removeAll(), 1.second) must be(org.apache.pekko.Done)
        Await.result(cacheApi.get[String]("foo"), 1.second) must beNone
      }
    }
  }
}

class CustomCacheManagerProvider @Inject() (cacheManagerProvider: CacheManagerProvider) extends Provider[CacheManager] {
  lazy val get = {
    val mgr = cacheManagerProvider.get
    mgr.close()
    mgr.init()
    mgr.removeCache("custom") // cache config is not cleared upon `close()` and causes auto-creation of cache on init()
    mgr.createCache(
      "custom",
      CacheConfigurationBuilder.newCacheConfigurationBuilder(
        classOf[String],
        classOf[EhExpirableCacheValue],
        ResourcePoolsBuilder.heap(100)
      )
    )
    mgr.getCache("custom", classOf[String], classOf[EhExpirableCacheValue])
    mgr
  }
}

class NamedCacheController @Inject() (
    @NamedCache("custom") val cache: SyncCacheApi,
    @NamedCache("custom") val asyncCache: AsyncCacheApi
)
