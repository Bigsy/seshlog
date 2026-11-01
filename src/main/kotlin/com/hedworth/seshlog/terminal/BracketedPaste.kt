package com.hedworth.seshlog.terminal

/** Adapted from codex-link: one paste event, without Enter, input clearing or clipboard changes. */
internal object BracketedPaste {
    fun validate(text: String) {
        require(text.none { (it < ' ' && it !in "\t\r\n") || it in '\u007f'..'\u009f' }) {
            "Terminal control characters are not supported."
        }
    }

    fun frame(text: String): String {
        validate(text)
        return "\u001b[200~" + text.replace("\r\n", "\n").replace('\r', '\n') + "\u001b[201~"
    }
}
