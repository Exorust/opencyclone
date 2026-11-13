package ai.opencyclon.engine

import ai.opencyclon.accessibility.ElementList
import ai.opencyclon.accessibility.UiElement

/** Resolves an element number from the screen the model saw against a fresh read of the screen (F18.1). */
object ElementTargets {
    sealed class Resolution {
        /** Tap [element]'s present centre. */
        data class Found(val element: UiElement) : Resolution()
        /** The number was on the screen the model saw, but its label changed or the element is gone. */
        object Changed : Resolution()
        /** The screen the model saw had no element with that number. */
        object Unknown : Resolution()
    }

    fun noElement(number: Int): String = "There is no element $number on this screen."
    const val SCREEN_CHANGED = "The screen changed. Look again."

    /**
     * The element with the same stable key on the fresh screen; else the element under the same number when it
     * still has the same label and class (it moved); else [Resolution.Changed]. A null [fresh] (unreadable) is Changed.
     */
    fun resolve(number: Int, seen: ElementList?, fresh: ElementList?): Resolution {
        val before = seen?.elements?.get(number) ?: return Resolution.Unknown
        fresh ?: return Resolution.Changed
        fresh.elements.values.firstOrNull { it.key == before.key }?.let { return Resolution.Found(it) }
        val same = fresh.elements[number]
        return if (same != null && same.label == before.label && same.className == before.className) {
            Resolution.Found(same)
        } else Resolution.Changed
    }
}
