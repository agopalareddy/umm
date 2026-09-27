package io.github.agopalareddy.umm.ime

sealed interface Insertion {
    data object Commit : Insertion
    data object Clipboard : Insertion
}

object InsertionDecider {
    /** Text goes into the field only if the field the dictation started in is still the active one. */
    fun decide(startedSession: Int, currentSession: Int, inputActive: Boolean): Insertion =
        if (startedSession == currentSession && inputActive) Insertion.Commit else Insertion.Clipboard
}
