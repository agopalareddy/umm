# Google Play listing

Copy these into Play Console. Assets are in this folder and in `docs/screenshots/`.

## Store listing

**App name** (30 characters max)

```
Umm: Voice Typing
```

**Short description** (80 characters max)

```
Talk in any app and get clean text. Pay per use with your own OpenRouter key.
```

**Full description** (4,000 characters max)

```
Umm is a voice keyboard. Switch to it in any text field and talk. When you stop, it types a cleaned-up version of what you said: filler words, false starts, and self-corrections come out.

You say: "Um, so, can we meet at five, no, six tomorrow?"
Umm types: "Can we meet at 6 tomorrow?"

PAY FOR WHAT YOU USE
Umm runs on your own OpenRouter account, so there is no subscription. A short dictation costs about $0.0004. At 100 dictations a day, that is roughly $1.20 a month. Set a monthly credit limit on your OpenRouter key, and see what you have spent on Umm's Account page.

YOU CHOOSE HOW MUCH IT REWRITES
Four cleanup levels: Raw, Light, Formatted, and Polished. Set a level per app category. Messaging defaults to Light, email and notes to Formatted, and you can move any app between categories from the keyboard.

QUIET SPEECH AND MIXED LANGUAGES
Double-tap the mic to keep recording through pauses or a whisper. Mixed speech such as Hinglish is kept as spoken, never translated, in Latin or native script.

PRIVATE BY DESIGN
Umm has no server, no account, no ads, and no tracking. Audio goes from your phone to OpenRouter with your key and nowhere else. Turn on "Zero data retention only" to use only providers that don't keep your data.

NOTHING GETS LOST
If the network fails, the audio is kept for a retry. If you have left the text field by the time the text is ready, it goes to the clipboard. History keeps your last 50 dictations.

WORKS ON OLDER PHONES
Android 10 or newer.

You need an OpenRouter account with a few dollars of credit. "Connect with OpenRouter" signs you in and creates a key for Umm, or you can paste a key you already have.

Umm is source-available: github.com/agopalareddy/umm
```

**Category:** Productivity

**Contact email:** adurs2002@gmail.com

**Website:** https://github.com/agopalareddy/umm

**Privacy policy:** https://agopalareddy.github.io/umm/privacy/

**Graphics**

- App icon: `icon-512.png` (512 × 512)
- Feature graphic: `feature-graphic.png` (1024 × 500)
- Phone screenshots: `../screenshots/keyboard.png`, `home.png`, `settings.png`, `account.png`, `models.png`,
  `dark.png`

## App access

Umm needs an OpenRouter key to transcribe. Select "All or some functionality is restricted" and give reviewers:

```
Umm uses the reviewer's own OpenRouter account. To test without one, paste this key on the setup screen
(step 3, "Or paste an OpenRouter key"): <REVIEW KEY>

Then open any text field, switch to the Umm keyboard from the keyboard switcher, and speak. The home screen's
"Try it here" box works too.
```

Create a separate key for this at https://openrouter.ai/settings/keys with a small credit limit (for example $2),
and delete it after review.

## Data safety

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes |
| Is all data encrypted in transit? | Yes (HTTPS) |
| Can users request that data be deleted? | Yes: History can be cleared in the app; uninstalling removes everything |
| Data types collected | Audio → Voice or sound recordings; App activity → Other user-generated content (dictated text) |
| Collected or shared? | Collected. Processed by OpenRouter and model providers as service providers for the app's function |
| Processed ephemerally? | No (providers may retain data unless zero data retention is on) |
| Required or optional? | Required: dictation doesn't work without it |
| Purpose | App functionality |

## Content rating

- Category: Utility / Productivity.
- The app uses generative AI to rewrite the user's own dictated text. It does not generate images, and users
  cannot share content with other users.
- In-app reporting of AI output: History → Report.

## Target audience

13 and older. Not designed for children.

## Ads

No ads.

## Before the first upload

- Personal developer accounts created after November 13, 2023 must run a closed test with at least 12 testers
  for 14 continuous days before applying for production.
- App signing: when enrolling in Play App Signing, choose to upload the existing Umm key (`~/.config/umm/`), so
  APKs from GitHub Releases and installs from Play can update each other.
- Upload the `.aab` from the GitHub Release.
