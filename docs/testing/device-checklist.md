# Umm v1 device checklist

Run on a real phone over wireless adb with the debug build (`./gradlew installDebug`).
Mark each line PASS or FAIL with a note.

| # | Check | Expected | Result |
|---|---|---|---|
| 1 | Fresh install → open Umm | Onboarding shows 3 incomplete steps | |
| 2 | Complete onboarding | Settings screen appears | |
| 3 | Disconnect → Connect with OpenRouter → sign in and approve | Browser returns to Umm; "Connected" | PASS 2026-09-29 (also after force-stop) |
| 4 | Google Keep note, switch to Umm, say one sentence | Text inserted; previous keyboard restored | |
| 5 | Gmail compose body, dictate a spoken list | Formatted: bullet list | |
| 6 | WhatsApp chat, dictate Hinglish | Light: Latin script, not translated | |
| 7 | Any password field, switch to Umm | "Voice input is off in password fields"; no recording | |
| 8 | Airplane mode on, dictate | "Couldn't reach OpenRouter" + Retry; after airplane off, Retry inserts | |
| 9 | Start dictating, switch to another field before it finishes | Text goes to clipboard with "Copied — field changed" | |
| 10 | Search bar (single-line), dictate a list at Formatted | Text lands on one line | |
| 11 | Dictate into the middle of existing text after a word | Exactly one space between words | |
| 12 | ~10 s dictation on mobile data | Text appears within ~3 s after you stop speaking | |
| 13 | Settings: silence timeout Off, pause 10 s mid-dictation | Keeps recording until Stop is tapped | |
| 14 | Keyboard app chip on an unassigned app → assign Email | App listed under Email in Categories | PASS 2026-09-29 |
| 15 | History: Re-clean an entry at Polished | Entry text updates | |

## Voice retest after the threshold fix (2026-09-29, Pixel 9 Pro, v1.1.0)

| Check | Result |
|---|---|
| Normal voice, one sentence, auto-stop | PASS |
| Normal voice with a ~1 s pause | PASS |
| Whisper with double-tap mic | PASS |
| Room noise only, no junk text | PASS |
| App chip assign | PASS |
