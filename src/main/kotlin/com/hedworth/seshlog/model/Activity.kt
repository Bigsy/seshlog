package com.hedworth.seshlog.model

/**
 * What the agent is doing, as far as its own storage says. Meaningful only while the session's
 * process is running; for a finished session it is the state the agent was left in.
 */
enum class Activity {
    /** Generating a reply or running tools. */
    WORKING,
    /** The turn ended (or failed) and the agent is waiting for the next prompt. */
    WAITING,
    /** The user stopped the turn; the agent is waiting for the next prompt. */
    INTERRUPTED,
    /** The agent records nothing usable, or Seshlog does not read it for this provider. */
    UNKNOWN,
}
