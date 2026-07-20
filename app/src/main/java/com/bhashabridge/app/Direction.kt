package com.bhashabridge.app

/**
 * Purpose:  Which way a translation runs.
 * Owns:     Nothing.
 * Lifetime: Process
 * Thread:   Any — immutable.
 *
 * Lives in the root package, not in `mt/`, deliberately. Three subsystems need it: `mt/` (which
 * model pair), `speech/` (which Vosk model, which TTS voice), and `ui/` (which labels). Placing it
 * in `mt/` would force `speech/ -> mt/` for a two-value enum with no behaviour — an entire
 * dependency edge bought to share a type. See DEPENDENCY_RULES.md D3.
 *
 * Having one symbol that every direction-sensitive path derives from is also the guard against
 * LESSONS_FROM_V3.md L11, where V3.4.1's audio-file import kept using the English recogniser in
 * HI->EN mode because nothing connected "new direction" to "every place direction is consumed".
 */
enum class Direction {
    EN_TO_HI,
    HI_TO_EN,
    ;

    fun opposite(): Direction = if (this == EN_TO_HI) HI_TO_EN else EN_TO_HI
}
