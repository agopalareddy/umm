# Accessibility Service Declaration

Google Play Permission Declaration Form answers and demo video shot list for Umm's floating dictation button (`BubbleService`).

---

## 1. Play Console Declaration Form Answers

### Is your app an accessibility tool?
**No.**
Umm is a voice-typing productivity tool that enables users to dictate cleaned-up text into any app. Umm does **not** set `android:isAccessibilityTool` in its service configuration (`bubble_service.xml`). That configuration declares `android:canRetrieveWindowContent="true"` and `android:accessibilityFlags="flagIncludeNotImportantViews"`. The flag is used only so the service can find the focused text field in apps whose custom or unlabelled input fields are marked "not important for accessibility". It is not used to read other on-screen content.

### Which Accessibility API capabilities does your app use?
- `AccessibilityService` overlay (`TYPE_ACCESSIBILITY_OVERLAY`), to draw the floating button
- Three event types: `typeViewFocused`, `typeViewTextSelectionChanged` and `typeWindowStateChanged`. Umm uses them to learn when an editable field gains or loses focus, so the button can show or hide. The content of these events is not read.
- Finding the focused input node (`rootInActiveWindow.findFocus(FOCUS_INPUT)`) and checking whether it is editable and whether Android reports it as a password field (`isEditable`, `isPassword`)
- Reading the focused field's current text and selection at delivery time, when the dictated text is ready, to place the insertion
- Text insertion into the focused field (`ACTION_SET_TEXT` and `ACTION_SET_SELECTION`). If the field refuses `ACTION_SET_TEXT`, Umm falls back to the clipboard and `ACTION_PASTE`.
- Identifying the package name of the focused field's app (`node.packageName`)
- The normal permission `QUERY_ADVANCED_PROTECTION_MODE`, used only to show a note in Settings on Android 16 (API 36) and newer when Advanced Protection blocks the floating button

### Why does the app need to use the Accessibility API?
Umm provides a floating dictation button that overlays the screen so users can dictate into text fields across any application on their device:
1. **Direct text insertion:** When the user dictates using the floating button, the service inserts the cleaned-up text directly into the currently focused editable field and positions the cursor at the end of the insertion.
2. **Per-app cleanup level:** It inspects the foreground app's package name to apply the user's category preference (e.g. Light cleanup for messaging apps, Formatted for email and notes).
3. **No keyboard switching required:** A soft keyboard alone requires users to switch their active input method back and forth every time they want to dictate. The floating button allows users to keep their preferred primary keyboard active (for swipe typing, autocorrect, and typing) while using Umm for push-to-talk voice dictation with a single touch.

### What data does the service access, and how is it handled?
- **Inactive until the user turns it on:** The service returns at once from every event and draws nothing until the user has turned on "Floating button" in Umm's settings. Enabling the service in Android's settings alone does not activate it.
- **Focused text field only:** When a dictation is delivered, the service reads the current text and selection range of the focused editable field to calculate spacing and cursor placement for the inserted text. Before that, it reads only whether the focused field is editable and whether it is a password field.
- **Password fields:** Umm checks whether Android reports the focused field as a password field (`node.isPassword`). Over such a field the floating button hides and recording is refused. Umm never reads the text of a field that Android reports as a password field. A password field that an app does not report as one cannot be detected.
- **No storage or transmission of screen content:** Field text and on-screen content are never recorded, saved to disk, or sent across the network.
- **Clipboard:** Umm only writes to the clipboard; it never reads it. The only text it writes is the dictated text, never the field's content. If the field refuses direct insertion, Umm puts the dictated text on the clipboard and pastes it, which replaces the previous clipboard contents. If the field is gone or has changed before the text is ready, the dictated text is copied to the clipboard and Umm shows a "Copied — field changed" message.
- **Audio transmission:** The user's spoken audio is recorded only during an active dictation session and sent exclusively to OpenRouter using the user's own API key for speech-to-text and cleanup.

### How is prominent disclosure and consent handled?
The first time the user turns on "Floating button" (and again after a Decline), before system settings are opened, Umm displays a dedicated, prominent modal dialog in the app:
> **Umm needs the Accessibility permission for the floating button.**  
> It is used to see which text field you have selected, so it can put your dictated text there, and which app it is in, to pick the cleanup level. Umm reads only the selected field's current text and cursor position, never password fields, and never reads or stores anything else on your screen. Your speech goes to OpenRouter as usual; nothing else leaves your phone.

The user must explicitly click **Accept** before the system Accessibility Settings screen is opened. If the user clicks **Decline**, the floating button stays off and Umm does not open Accessibility settings.

---

## 2. Demo Video Shot List

The demo video must be publicly accessible on YouTube (unlisted) or Google Drive.

| Shot | Action | Description |
|---|---|---|
| **1. In-app navigation** | Settings → Floating button | User opens Umm, navigates to Settings, and taps "Floating button". |
| **2. Prominent disclosure** | Toggle switch on | User switches on "Floating button". The prominent disclosure dialog appears with the exact text explaining permission usage. |
| **3. Explicit consent** | Tap "Accept" | User reads disclosure and taps "Accept". The app launches system Accessibility settings. |
| **4. Enabling service** | System Accessibility Settings | User opens Umm's entry in Accessibility settings and turns it on. (If sideloaded, shows the Restricted Settings step from the in-app "Can't turn it on?" help: App info → ⋮ → Allow restricted settings.) |
| **5. Overlay appearance** | Return to Umm | Umm displays "Active" status. The floating button appears docked at the screen edge. |
| **6. Push-to-talk dictation** | Hold to talk in another app | User switches to a messaging or notes app, focuses a text field, presses and holds the floating button, speaks a short sentence with a filler word, and releases. The cleaned-up text is inserted into the field. |
| **7. Tap dictation** | Tap to speak | User taps the button, speaks, and pauses. With "Stop after silence" on (Settings → Dictation, on by default), the recording stops after the silence timeout and the text is inserted. With it off, the user taps the button again to stop. |
| **8. Double-tap continuous** | Double-tap for continuous mode | User double-taps the button (outer ring displays), speaks through pauses, and taps to finish. |
| **9. Password field safety** | Tap a password field | User taps a password input field. The floating button hides and cannot record. |
| **10. Disabling** | Turn off in Settings | User returns to Umm Settings and switches off Floating button; overlay is removed immediately. |
