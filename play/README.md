# Publishing on Google Play

Everything Play Console asks for, with the answers for AI Markdown Reader. The store texts and graphics are in
[listings/en-US](listings/en-US); [the release workflow](../.github/workflows/release.yml) builds the signed app.

## 1. Developer account (once)

On the Play Console home page, finish the three verifications: **identity** (an official document; takes up to a
few days), **phone number**, and **access to an Android device** (install **Google Play Console** from the Play
Store on your phone and sign in with the developer account).

A personal account made after 13 November 2023 must run a **closed test with at least 12 testers, opted in for 14
days in a row**, before the app can be published to everyone (section 5). Start finding testers early: friends,
family and colleagues with an Android phone. Recruit 15 or so, in case some drop out.

## 2. Create the app

**Home → Create app**:

| Field | Answer |
| --- | --- |
| App name | AI Markdown Reader: Read Aloud |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free |
| Declarations | Accept both (Developer Program Policies, US export laws) |

The package name is set by the first upload: `com.orexii.aimarkdownreader`. It can never change.

## 3. Store listing

**Grow users → Store presence → Main store listing**:

| Field | Source |
| --- | --- |
| App name | [title.txt](listings/en-US/title.txt) |
| Short description | [short-description.txt](listings/en-US/short-description.txt) |
| Full description | [full-description.txt](listings/en-US/full-description.txt) |
| App icon | [graphics/icon.png](listings/en-US/graphics/icon.png) (512 × 512) |
| Feature graphic | [graphics/feature-graphic.png](listings/en-US/graphics/feature-graphic.png) (1024 × 500) |
| Phone screenshots | [graphics/phone-*.png](listings/en-US/graphics) (1080 × 1920; from the **Store screenshots** workflow, which can retake them) |
| Tablet screenshots (7-inch and 10-inch) | [graphics/tablet-*.png](listings/en-US/graphics) (2560 × 1440, 16:9) |

**Store settings**: category **Productivity**; tags such as Text to speech and Reading; contact email (shown on
the listing); website `https://github.com/Orexii/ai-markdown-reader-android`.

## 4. App content (Policy → App content)

| Declaration | Answer |
| --- | --- |
| Privacy policy | `https://github.com/Orexii/ai-markdown-reader-android/blob/main/PRIVACY.md` |
| Ads | No, the app doesn't contain ads |
| App access | All functionality is available without any access restrictions |
| Content rating | Category **Utility, Productivity, Communication, or Other**; answer **No** to every question (no violence, sexuality, language, controlled substances, gambling, user interaction or content sharing, location sharing, digital purchases, or unrestricted internet access) |
| Target audience | 13–15, 16–17 and 18 and over. The app is not designed to appeal to children |
| News app | No |
| Data safety | **No** data collected, **no** data shared (documents are processed on the phone; the voice download is a file download the user asks for; ONNX Runtime's usage telemetry is removed from the app, which the release build test checks). The form then skips the remaining questions |
| Government app | No |
| Financial features | My app doesn't provide any financial features |
| Health | My app does not have any health features |
| Advertising ID | No (the app has no `AD_ID` permission) |
| Foreground service permissions | See below |

**Foreground service permissions**: tick **Media playback** (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`).

- Feature: *"Reads Markdown documents aloud. After the user presses Play, reading continues with the screen off or
  while another app is open, with controls in the notification, on the lock screen and on Bluetooth headphones."*
- Impact if deferred or interrupted: *"Reading stops in the middle of a sentence; the user loses their place and
  must reopen the app to continue listening."*
- Video: a screen recording that opens a document, taps Play, goes to the home screen and shows the notification
  while reading continues ([fgs-media-playback.mp4](fgs-media-playback.mp4) in this folder, linked from the
  repository, or uploaded to YouTube as unlisted).

## 5. Testing

1. **Internal testing** (Test and release → Testing → Internal testing): create a release, keep **Google-generated
   app signing key** (Play App Signing), upload `ai-markdown-reader-<version>.aab` from the GitHub release, add
   yourself as a tester, roll out. Install it from the opt-in link to check the Play build.
2. **Closed testing** (Testing → Closed testing → Alpha, or a new track): add the testers (an email list, or a
   Google Group they join), choose countries, promote the internal release to this track and send it for review.
   Share the **opt-in link**; each tester opens it on their phone, accepts, and installs from Play. The 14 days
   start once 12 testers are opted in, and restart if fewer than 12 stay opted in.
3. After 14 days, **Dashboard → Apply for production** and answer the short questionnaire about the test.

Updates during the closed test are fine: bump the version, publish a GitHub release, upload the new bundle.

## 6. Production

**Production → Create new release**, promote the tested release, write release notes (the workflow makes
`whatsnew-en-US` from the CHANGELOG), and roll out (a staged rollout, for example 20%, is possible). Google reviews
each release; the first one can take a few days.

## 7. Uploads from GitHub (optional)

After the first manual upload, the release workflow can upload to Play itself, with no key stored, through Workload
Identity Federation (the same idea as the extension's Trusted Publishing):

1. In Google Cloud (any project): enable the **Google Play Android Developer API**, create a service account
   `play-publisher`, and a workload identity pool and OIDC provider for `https://token.actions.githubusercontent.com`
   restricted to `assertion.repository == 'Orexii/ai-markdown-reader-android'`. Allow the provider's principal set
   to impersonate the service account (**Workload Identity User**).
2. In Play Console, **Users and permissions → Invite new users**: the service account's email, with access to this
   app and the **Release to testing tracks** (and, when ready, production) permissions.
3. In the GitHub repository, add the variables `GCP_WORKLOAD_IDENTITY_PROVIDER` (the provider's full resource name)
   and `GCP_SERVICE_ACCOUNT` (its email), and optionally `PLAY_TRACK` (`internal`, `alpha`, `beta` or `production`).

Without them, the workflow still builds and attaches the signed bundle; upload it in Play Console.

## Signing

The **upload key** signs what is sent to Play; Google re-signs installs with the app signing key it keeps. The upload
key is in `~/.config/ai-markdown-reader-android/` on the development machine (with its passwords in
`keystore.properties`, which the repository's `keystore.properties` links to) and in the repository secrets
`AMR_KEYSTORE_BASE64`, `AMR_KEYSTORE_PASSWORD`, `AMR_KEY_ALIAS` and `AMR_KEY_PASSWORD`. Keep a copy of that folder
somewhere safe. If the key is ever lost, Play Console → App integrity → **Request upload key reset**.

Upload certificate SHA-256: `76:9D:45:47:3B:A6:DC:03:32:5E:02:17:60:EE:9C:2C:92:A2:B7:C5:AD:2F:C1:62:ED:F8:CB:DD:65:5F:05:4C`
