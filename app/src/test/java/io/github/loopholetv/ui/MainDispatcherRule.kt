package io.github.loopholetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Sets [dispatcher] as Main for each test and owns the view models a test builds, so their
 * collectors end with the test instead of running into the next test's Main.
 *
 * Wrap each view model in [keep]. A test that closes a database in `@After` calls [clear] first;
 * the rule clears again (harmlessly) and then resets Main once the `@After` methods have run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    private val store = ViewModelStore()
    private var count = 0

    /** Registers [vm] so [clear] cancels its scope. */
    fun <T : ViewModel> keep(vm: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        return ViewModelProvider(store, factory)["vm${count++}", vm.javaClass]
    }

    /** Clears every kept view model, cancelling its coroutines. */
    fun clear() = store.clear()

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) {
        clear()
        Dispatchers.resetMain()
    }
}
