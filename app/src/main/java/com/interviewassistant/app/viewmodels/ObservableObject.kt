package com.interviewassistant.app.viewmodels

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mirror of `InterviewAssistant.App/ViewModels/ObservableObject.cs`. */

/**
 * Base class for the view models: the C#'s `INotifyPropertyChanged` + `Set<T>`/`Raise`, carried on
 * StateFlow (TRANSFER-MAP §6.2: StateFlow replaces INotifyPropertyChanged).
 *
 * The property pattern P4-C2/C3 use — one line per C# property, same name, same shape:
 *
 * ```
 * var statusText: String by bindable("")
 *     private set
 * ```
 *
 * A [bindable] property reads and writes exactly like a plain property. Assignment runs the C#
 * `Set<T>` semantics: an equal value (Kotlin `==`, the `EqualityComparer<T>.Default` counterpart)
 * is a no-op — no notification, nothing changes; a real change stores the value and notifies the
 * property-changed listeners with the property's name (the delegate takes the name from the
 * property itself, the `[CallerMemberName]` counterpart). Behind every bindable property sits a
 * [MutableStateFlow]; Compose collects it through [propertyFlow] (`collectAsState`), and plain
 * listeners subscribe through [addPropertyChangedListener] (the `PropertyChanged` event).
 * [raise] is the C#'s `Raise`: an unconditional notification for computed properties — the name
 * is passed explicitly because Kotlin has no `[CallerMemberName]` for a plain call (DC-84).
 */
abstract class ObservableObject {
    private val propertyChangedListeners = mutableListOf<(String) -> Unit>()
    private val bindables = mutableMapOf<String, Bindable<*>>()

    /** The C#'s `PropertyChanged` event: the listener receives the changed property's name. */
    fun addPropertyChangedListener(listener: (String) -> Unit) {
        synchronized(propertyChangedListeners) { propertyChangedListeners.add(listener) }
    }

    fun removePropertyChangedListener(listener: (String) -> Unit) {
        synchronized(propertyChangedListeners) { propertyChangedListeners.remove(listener) }
    }

    private fun firePropertyChanged(name: String) {
        val snapshot = synchronized(propertyChangedListeners) { propertyChangedListeners.toList() }
        for (listener in snapshot) listener(name)
    }

    /** Unconditional change notification for a computed property (the C#'s `Raise`). */
    protected fun raise(name: String) = firePropertyChanged(name)

    /** Creates the delegate behind one bindable property (see the class doc for the pattern). */
    protected fun <T> bindable(initial: T): Bindable<T> = Bindable(initial)

    /**
     * The [StateFlow] behind the named bindable property — how the Compose layer observes it.
     * Throws when the property does not exist or was never touched (a bindable registers itself
     * on first read or write, mirroring a backing field that exists from construction).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> propertyFlow(name: String): StateFlow<T> =
        (bindables[name] as? Bindable<T>)?.stateFlow
            ?: throw IllegalArgumentException("No bindable property '$name' on ${javaClass.simpleName}.")

    /** The delegate behind a bindable property: the backing [MutableStateFlow] plus `Set<T>` semantics. */
    inner class Bindable<T>(initial: T) : ReadWriteProperty<ObservableObject, T> {
        private val flow = MutableStateFlow(initial)

        /** The property's observable state (the same flow [propertyFlow] returns). */
        val stateFlow: StateFlow<T> = flow.asStateFlow()

        override fun getValue(thisRef: ObservableObject, property: KProperty<*>): T {
            register(property.name)
            return flow.value
        }

        override fun setValue(thisRef: ObservableObject, property: KProperty<*>, value: T) {
            register(property.name)
            if (flow.value == value) return // Set<T>: equal value — no store, no notification
            flow.value = value
            firePropertyChanged(property.name)
        }

        private fun register(name: String) {
            synchronized(bindables) { if (!bindables.containsKey(name)) bindables[name] = this }
        }
    }
}

/**
 * Mirror of the C#'s `RelayCommand` (`ICommand`): an action plus an optional can-execute predicate.
 *
 * [execute] runs the action unconditionally (the C#'s `Execute` does not consult `CanExecute`);
 * [canExecute] evaluates the predicate live on every call, exactly like the C#. What Android does
 * not have is WPF's `CommandManager.RequerySuggested` — the global broadcast the C#'s
 * `CanExecuteChanged` event silently subscribes every listener to (DC-84). The counterpart here:
 * [canExecuteFlow] holds the last evaluated value for Compose (`collectAsState`), refreshed
 * whenever [raiseCanExecuteChanged] is called, and [addCanExecuteChangedListener] subscribers are
 * notified by the same call — requery is explicit, driven by the view model that owns the command.
 */
class RelayCommand(private val run: () -> Unit, private val can: (() -> Boolean)? = null) {
    private val canExecuteChangedListeners = mutableListOf<() -> Unit>()
    private val _canExecuteFlow = MutableStateFlow(can?.invoke() ?: true)

    /** The last evaluated can-execute value, for Compose observation; see [raiseCanExecuteChanged]. */
    val canExecuteFlow: StateFlow<Boolean> = _canExecuteFlow.asStateFlow()

    fun canExecute(): Boolean = can?.invoke() ?: true

    fun execute() = run()

    /** The C#'s `CanExecuteChanged`: re-evaluates the predicate, updates [canExecuteFlow], notifies. */
    fun raiseCanExecuteChanged() {
        _canExecuteFlow.value = canExecute()
        val snapshot = synchronized(canExecuteChangedListeners) { canExecuteChangedListeners.toList() }
        for (listener in snapshot) listener()
    }

    fun addCanExecuteChangedListener(listener: () -> Unit) {
        synchronized(canExecuteChangedListeners) { canExecuteChangedListeners.add(listener) }
    }

    fun removeCanExecuteChangedListener(listener: () -> Unit) {
        synchronized(canExecuteChangedListeners) { canExecuteChangedListeners.remove(listener) }
    }
}
