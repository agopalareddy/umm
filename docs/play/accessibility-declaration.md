# Accessibility Service Declaration

Google Play Permission Declaration Form answers and demo video shot list for Umm's floating dictation button (`BubbleService`).

---

## 1. Play Console Declaration Form Answers

### Is your app an accessibility tool?
**No.**
Umm is a voice-typing productivity tool that enables users to dictate cleaned-up text into any app. Umm does **not** declare `android:accessibilityFlags="flagIncludeNotImportantViews"` for accessibility tool purposes and does **not** declare `isAccessibilityTool="true"` in its service configuration (`bubble_service.xml`).

### Which Accessibility API capabilities does your app use?
- `AccessibilityService` overlay (`TYPE_ACCESSIBILITY_OVERLAY`)
- Reading the focused editable input node (`rootInActiveWindow.findFocus(FOCUS_INPUT)`)
- Text insertion into the focused field (`ACTION_SET_TEXT`, `ACTION_SET_SELECTION`, and clipboard paste fallback `ACTION_PASTE`)
- Identifying the foreground application package name (`node.packageName`)

### Why does the app need to use the Accessibility API?
Umm provides a floating dictation button that overlays the screen so users can dictate into text fields across any application on their device:
1. **Direct text insertion:** When the user dictates using the floating button, the service inserts the cleaned-up text directly into the currently focused editable field and positions the cursor at the end of the insertion.
2. **Per-app cleanup level:** It inspects the foreground app's package name to apply the user's category preference (e.g. Light cleanup for messaging apps, Formatted for email and notes).
3. **No keyboard switching required:** A soft keyboard alone requires users to switch their active input method back and forth every time they want to dictate. The floating button allows users to keep their preferred primary keyboard active (for swipe typing, autocorrect, and typing) while using Umm for push-to-talk voice dictation with a single touch.

### What data does the service access, and how is it handled?
- **Focused text field only:** The service reads only the current text and selection range of the active, focused editable field to calculate spacing and cursor placement for inserted text.
- **Never password fields:** The service checks `node.isPassword`. Over password fields, the floating button automatically hides and refuses recording.
- **No storage or transmission of screen content:** Field text and on-screen content are never recorded, saved to disk, or sent across the network.
- **Audio transmission:** The user's spoken audio is recorded only during an active dictation session and sent exclusively to OpenRouter using the user's own API key for speech-to-text and cleanup.

### How is prominent disclosure and consent handled?
Before the accessibility permission is requested or system settings are opened, Umm displays a dedicated, prominent modal dialog in the app:
> **Umm needs the Accessibility permission for the floating button.**  
> It is used to see which text field you have selected, so it can put your dictated text there, and which app it is in, to pick the cleanup level. Umm reads only the selected field's current text and cursor position, never password fields, and never reads or stores anything else on your screen. Your speech goes to OpenRouter as usual; nothing else leaves your phone.

The user must explicitly click **Accept** before the system Accessibility Settings screen is opened. If the user clicks **Decline**, the service remains disabled and the permission is not requested.

---

## 2. Demo Video Shot List

The demo video must be publicly accessible on YouTube (unlisted) or Google Drive.

| Shot | Action | Description |
|---|---|---|
| **1. In-app navigation** | Settings → Floating button | User opens Umm, navigates to Settings, and taps "Floating button". |
| **2. Prominent disclosure** | Toggle switch on | User switches on "Floating button". The prominent disclosure dialog appears with the exact text explaining permission usage. |
| **3. Explicit consent** | Tap "Accept" | User reads disclosure and taps "Accept". The app launches system Accessibility settings. |
| **4. Enabling service** | System Accessibility Settings | User locates "Umm" in Downloaded apps/services. (If sideloaded, shows the Restricted Settings step: App info → ⋮ → Allow restricted settings). User enables the Umm accessibility service. |
| **5. Overlay appearance** | Return to Umm | Umm displays "Active" status. The floating button appears docked at the screen edge. |
| **6. Push-to-talk dictation** | Hold to talk in another app | User switches to a messaging or notes app, focuses a text field, presses and holds the floating button, speaks: *"Um, hello, can we meet at noon?"*, and releases. Text is inserted cleanly as *"Can we meet at 12:00?"*. |
| **7. Tap dictation** | Tap to speak | User taps the button, speaks, pauses; silence timeout auto-stops and inserts the text. |
| **8. Double-tap continuous** | Double-tap for continuous mode | User double-taps the button (outer ring displays), speaks through pauses, and taps to finish. |
| **9. Password field safety** | Tap a password field | User taps a password input field. The floating button instantly hides and cannot record. |
| **10. Disabling** | Turn off in Settings | User returns to Umm Settings and switches off Floating button; overlay is removed immediately. |
