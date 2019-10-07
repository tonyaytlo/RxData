package com.revolut.rxdata.dod

import com.nhaarman.mockito_kotlin.*
import io.reactivex.Single
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

private typealias Params = Int
private typealias Key = String
private typealias Domain = String

class DataObservableDelegateTest {

    private val params: Params = 0

    private val cachedDomain: Domain = "cached_domain_model"

    private val domain: Domain = "domain_model"

    private val key: Key = "key"

    private val backendException = IOException("HTTP 500. All tests are green!")

    private lateinit var fromNetwork: (Params) -> Single<Domain>

    private lateinit var toMemory: (Key, Params, Domain) -> Unit

    private lateinit var fromMemory: (Key, Params) -> Domain

    private lateinit var toStorage: (Key, Params, Domain) -> Unit

    private lateinit var fromStorage: (Key, Params) -> Domain

    private lateinit var paramsKey: (Params) -> Key

    private lateinit var dataObservableDelegate: DataObservableDelegate<Params, Key, Domain>

    private val computationScheduler: TestScheduler = TestScheduler()
    private val ioScheduler: TestScheduler = TestScheduler()

    @Before
    fun setUp() {
        fromNetwork = mock()
        toMemory = mock()
        fromMemory = mock()
        toStorage = mock()
        fromStorage = mock()
        paramsKey = mock()

        dataObservableDelegate = DataObservableDelegate(
            fromNetwork = fromNetwork,
            fromMemory = fromMemory,
            toMemory = toMemory,
            fromStorage = fromStorage,
            toStorage = toStorage,
            paramsKey = paramsKey
        )

        RxJavaPlugins.setIoSchedulerHandler { ioScheduler }
        RxJavaPlugins.setComputationSchedulerHandler { computationScheduler }
    }

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)
        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == null && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == domain && it.error == null && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verify(toMemory, only()).invoke(eq(key), eq(params), eq(domain))
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verify(toStorage, only()).invoke(eq(key), eq(params), eq(domain))
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(fromStorage.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == domain && it.error == null && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verify(toMemory).invoke(eq(key), eq(params), eq(cachedDomain))
        verify(toMemory).invoke(eq(key), eq(params), eq(domain))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verify(toStorage, only()).invoke(eq(key), eq(params), eq(domain))
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `FORCE observing data when memory cache IS NOT EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(fromMemory.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && it.loading
        }

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(1) {
            it.content == domain && it.error == null && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verify(toMemory, only()).invoke(eq(key), eq(params), eq(domain))
        verifyNoMoreInteractions(fromStorage)
        verify(toStorage, only()).invoke(eq(key), eq(params), eq(domain))
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == null && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == null && it.error == backendException && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toStorage)
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        whenever(fromStorage.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == cachedDomain && it.error == backendException && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verify(toMemory, only()).invoke(eq(key), eq(params), eq(cachedDomain))
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toStorage)
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `FORCE observing data when memory cache IS NOT EMPTY  and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        whenever(fromMemory.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && it.loading
        }

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(1) {
            it.content == cachedDomain && it.error == backendException && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toMemory)
        verifyNoMoreInteractions(fromStorage)
        verifyNoMoreInteractions(toStorage)
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == null && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == domain && it.error == null && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verify(toMemory, only()).invoke(eq(key), eq(params), eq(domain))
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verify(toStorage, only()).invoke(eq(key), eq(params), eq(domain))
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(fromStorage.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == domain && it.error == null && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verify(toMemory).invoke(eq(key), eq(params), eq(cachedDomain))
        verify(toMemory).invoke(eq(key), eq(params), eq(domain))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verify(toStorage).invoke(eq(key), eq(params), eq(domain))
        verify(paramsKey).invoke(eq(params))
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS NOT EMPTY`() {
        whenever(fromMemory.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && !it.loading
        }

        verifyNoMoreInteractions(fromNetwork)
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toMemory)
        verifyNoMoreInteractions(fromStorage)
        verifyNoMoreInteractions(toStorage)
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == null && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == null && it.error == backendException && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toStorage)
        verify(paramsKey, only()).invoke(eq(params))
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        whenever(fromStorage.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()
        testObserver.assertValueCount(0)

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && it.loading
        }
        testObserver.assertValueAt(1) {
            it.content == cachedDomain && it.error == backendException && !it.loading
        }

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(key), eq(params))
        verify(toMemory, only()).invoke(eq(key), eq(params), eq(cachedDomain))
        verify(fromStorage, only()).invoke(eq(key), eq(params))
        verifyNoMoreInteractions(toStorage)
        verify(paramsKey).invoke(eq(params))
    }

    @Test
    fun `observing data for MORE THAN ONE observer`() {
        var firstTime = true

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable {
            if (firstTime) {
                firstTime = false
                domain
            } else {
                throw backendException
            }
        })
        whenever(fromMemory.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val testObserver1 =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        testObserver1.assertValueCount(1)
        testObserver1.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && !it.loading
        }

        // refresh with result
        val testObserver2 =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver1.assertValueCount(2)
        testObserver1.assertValueAt(1) {
            it.content == cachedDomain && it.error == null && it.loading
        }

        testObserver2.assertValueCount(1)
        testObserver2.assertValueAt(0) {
            it.content == cachedDomain && it.error == null && it.loading
        }

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver1.assertValueCount(3)
        testObserver1.assertValueAt(2) {
            it.content == domain && it.error == null && !it.loading
        }

        testObserver2.assertValueCount(2)
        testObserver2.assertValueAt(1) {
            it.content == domain && it.error == null && !it.loading
        }

        verify(toMemory).invoke(eq(key), eq(params), eq(domain))

        whenever(fromMemory.invoke(eq(key), eq(params))).thenReturn(domain)

        //refresh with error
        val testObserver3 =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver1.assertValueCount(4)
        testObserver1.assertValueAt(3) {
            it.content == domain && it.error == null && it.loading
        }

        testObserver2.assertValueCount(3)
        testObserver2.assertValueAt(2) {
            it.content == domain && it.error == null && it.loading
        }

        testObserver3.assertValueCount(1)
        testObserver3.assertValueAt(0) {
            it.content == domain && it.error == null && it.loading
        }

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver1.assertValueCount(5)
        testObserver1.assertValueAt(4) {
            it.content == domain && it.error == backendException && !it.loading
        }

        testObserver2.assertValueCount(4)
        testObserver2.assertValueAt(3) {
            it.content == domain && it.error == backendException && !it.loading
        }

        testObserver3.assertValueCount(2)
        testObserver3.assertValueAt(1) {
            it.content == domain && it.error == backendException && !it.loading
        }
    }

    @Test
    fun `re-subscribing to constructed stream re-fetches memory cache`() {
        whenever(fromMemory.invoke(eq(key), eq(params))).thenReturn(cachedDomain)
        whenever(paramsKey.invoke(eq(params))).thenReturn(key)

        val observable = dataObservableDelegate.observe(params, forceReload = true)

        observable.test().awaitCount(1).dispose()
        verify(fromMemory, times(1)).invoke(eq(key), eq(params))

        observable.test().awaitCount(1).dispose()

        verify(fromMemory, times(2)).invoke(eq(key), eq(params))
    }

}