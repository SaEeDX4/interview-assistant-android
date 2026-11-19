package com.interviewassistant.app.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ObservableObject/RelayCommand mirror (P4-C1): the C# semantics of `Set<T>` (equality guard +
 * named notification), `Raise` (unconditional) and `RelayCommand` (live can-execute evaluation,
 * unconditional Execute). The C# has no dedicated unit tests for these 28 lines — they are exercised
 * through every view model — so the expectations are the C# source's own contract, asserted directly.
 */
class ObservableObjectTests {
    private class TestVm : ObservableObject() {
        var statusText: String by bindable("")
        var count: Int by bindable(0)
        fun pokeStatus() = raise("statusText")
    }

    @Test
    fun bindable_Set_RaisesWithThePropertyName_OncePerRealChange() {
        val vm = TestVm()
        val raised = mutableListOf<String>()
        vm.addPropertyChangedListener { raised += it }
        vm.statusText = "listening"
        vm.count = 3
        assertEquals(listOf("statusText", "count"), raised)
        assertEquals("listening", vm.statusText)
        assertEquals(3, vm.count)
    }

    @Test
    fun bindable_SetEqualValue_IsANoOp_NoNotification() {
        val vm = TestVm()
        vm.statusText = "ready"
        val raised = mutableListOf<String>()
        vm.addPropertyChangedListener { raised += it }
        vm.statusText = "ready" // Set<T>'s EqualityComparer guard: nothing happens
        assertTrue(raised.isEmpty())
        assertEquals("ready", vm.statusText)
    }

    @Test
    fun bindable_Flow_ReflectsTheCurrentValue() {
        val vm = TestVm()
        vm.statusText = "one"
        val flow = vm.propertyFlow<String>("statusText")
        assertEquals("one", flow.value)
        vm.statusText = "two"
        assertEquals("two", flow.value)
    }

    @Test
    fun raise_FiresUnconditionally_EvenWithoutAValueChange() {
        val vm = TestVm()
        val raised = mutableListOf<String>()
        vm.addPropertyChangedListener { raised += it }
        vm.pokeStatus()
        vm.pokeStatus()
        assertEquals(listOf("statusText", "statusText"), raised)
    }

    @Test
    fun removePropertyChangedListener_StopsNotifications() {
        val vm = TestVm()
        val raised = mutableListOf<String>()
        val listener: (String) -> Unit = { raised += it }
        vm.addPropertyChangedListener(listener)
        vm.statusText = "a"
        vm.removePropertyChangedListener(listener)
        vm.statusText = "b"
        assertEquals(listOf("statusText"), raised)
    }

    @Test
    fun relayCommand_NoPredicate_CanExecuteIsTrue_ExecuteRuns() {
        var runs = 0
        val cmd = RelayCommand({ runs++ })
        assertTrue(cmd.canExecute())
        assertTrue(cmd.canExecuteFlow.value)
        cmd.execute()
        assertEquals(1, runs)
    }

    @Test
    fun relayCommand_Predicate_IsEvaluatedLive_ExecuteIgnoresIt() {
        var allowed = false
        var runs = 0
        val cmd = RelayCommand({ runs++ }, { allowed })
        assertFalse(cmd.canExecute())
        cmd.execute() // ICommand.Execute does not consult CanExecute in the C# either
        assertEquals(1, runs)
        allowed = true
        assertTrue(cmd.canExecute()) // live evaluation, no requery needed for the direct call
    }

    @Test
    fun relayCommand_RaiseCanExecuteChanged_UpdatesTheFlowAndNotifies() {
        var allowed = false
        val cmd = RelayCommand({}, { allowed })
        var notifications = 0
        cmd.addCanExecuteChangedListener { notifications++ }
        assertFalse(cmd.canExecuteFlow.value)
        allowed = true
        cmd.raiseCanExecuteChanged()
        assertTrue(cmd.canExecuteFlow.value)
        assertEquals(1, notifications)
    }
}
