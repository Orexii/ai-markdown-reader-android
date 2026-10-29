# Privacy policy

AI Markdown Reader: Read Aloud (Android app `com.orexii.aimarkdownreader`, by Orexii). Last updated 29 October 2026.

**The short version: your documents stay on your phone. The app collects nothing and sends nothing about you.**

## What the app does with your data

- **Your documents** are opened and read aloud on your phone. Their text is never uploaded or shared. The app
  only reads the files and folders you choose to open, and remembers which ones you opened recently and where
  you stopped reading, on your phone.
- **Speech** is generated on your phone, by the natural voice or by your phone's own text-to-speech voice. If you
  choose the phone's voice, the text is spoken by the text-to-speech app installed on your phone (for example
  Speech Services by Google), under that app's own privacy policy.
- **No accounts, no ads, no analytics, no tracking, no crash reporting.** The app has no servers.

## Network access

The app connects to the internet only to:

- **Download the natural voice** when you ask for it: model and voice files from a fixed revision of
  https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped. Like any download, this request
  reveals your IP address to Hugging Face. No document text is sent. Each file's size and SHA-256 checksum are
  verified before use.
- **Show images from the web** that a document you open links to (for example `![logo](https://...)`), the same
  way a browser would. The image's server sees your IP address.
- **Open links** you tap, in your browser.

## Data stored on your phone

Settings, the list of recent documents, reading positions and the downloaded voice are stored in the app's
private storage. Android may include settings and reading positions in your device backup; the downloaded voice
is excluded. **Settings → Remove the downloaded natural voice** deletes the voice; uninstalling the app deletes
everything.

## Children

The app does not knowingly collect any information from anyone, including children.

## Changes and contact

Changes to this policy are published at
https://github.com/Orexii/ai-markdown-reader-android/blob/main/PRIVACY.md. Questions: open an issue at
https://github.com/Orexii/ai-markdown-reader-android/issues.
