# Third-party notices

AI Markdown Reader for Android's own source code is MIT-licensed (see LICENSE). The app (APK and app bundle)
also contains the components below.

**eSpeak NG.** The app includes eSpeak NG, compiled from source (third_party/espeak-ng, revision b864eac5), and
its English data. eSpeak NG is licensed under the GNU General Public License version 3 or later, so the app,
taken as a whole, is distributed under GPL-3.0-or-later (licenses/GPL-3.0.txt). The complete corresponding
source is this repository (https://github.com/Orexii/ai-markdown-reader-android) with its submodules: the VS
Code extension (https://github.com/Orexii/ai-markdown-reader) and eSpeak NG (https://github.com/espeak-ng/espeak-ng).

**Kokoro-82M.** The natural voice model and voice files are not included in the app. They are downloaded on
request from https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped (an ONNX conversion of
https://huggingface.co/hexgrad/Kokoro-82M) and are licensed under the Apache License 2.0 (licenses/Apache-2.0.txt).

**misaki.** The app's lexicon/us.json and lexicon/gb.json are the English pronunciation lexicons of misaki
(https://github.com/hexgrad/misaki, commit fba1236595f2d2bf21d414ba6e57d25256afada3), Copyright hexgrad, licensed under the
Apache License 2.0. core/.../Lexicon.kt and the toMisaki mapping in core/.../Misaki.kt are ports of misaki's word
lookup and eSpeak fallback rules (through the extension's TypeScript).

**kokoro.js.** The eSpeak phoneme corrections in Misaki.kt and the number and currency rules in Pronounce.kt
follow the approach of kokoro.js (https://github.com/hexgrad/kokoro, Apache-2.0).

**Sonic.** core/src/main/java/.../audio/Sonic.java is Sonic from AndroidX Media (Copyright 2017 The Android
Open Source Project; Copyright 2010 Bill Cox, https://github.com/waywardgeek/sonic), Apache License 2.0.

**ONNX Runtime.** The app includes ONNX Runtime 1.31.1 for Android (MIT, Copyright (c) Microsoft Corporation;
licenses/onnxruntime-LICENSE.txt) with the notices in licenses/onnxruntime-ThirdPartyNotices.txt.

**AndroidX, Jetpack Compose, Media3, Kotlin and kotlinx.** Apache License 2.0 (licenses/Apache-2.0.txt), from
https://android.googlesource.com, https://github.com/androidx/media, https://github.com/JetBrains/kotlin and
https://github.com/Kotlin. Media3 uses Guava (https://github.com/google/guava, Apache License 2.0).

**AI Markdown Reader for VS Code.** The reader page's renderer and narration builder (extension/src/document.ts,
extension/src/preview.ts), extension/media/preview.js and preview.css, and the Wagyu sample documents and art come
from https://github.com/Orexii/ai-markdown-reader (MIT, Copyright (c) 2026 Orexii).

The reader page (app/src/main/assets/reader/reader.js) bundles these npm packages:

## entities 4.5.0

License: BSD-2-Clause. Source: git://github.com/fb55/entities.git

```
Copyright (c) Felix Böhm
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## linkify-it 5.0.2

License: MIT. Source: markdown-it/linkify-it

```
Copyright (c) 2015 Vitaly Puzrin.

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
```

## markdown-it 14.3.3

License: MIT. Source: markdown-it/markdown-it

```
Copyright (c) 2014 Vitaly Puzrin, Alex Kocharin.

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
```

## mdurl 2.1.0

License: MIT. Source: https://github.com/markdown-it/mdurl.git

```
Copyright (c) 2015 Vitaly Puzrin, Alex Kocharin.

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

--------------------------------------------------------------------------------

.parse() is based on Joyent's node.js `url` code:

Copyright Joyent, Inc. and other Node contributors. All rights reserved.
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to
deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
IN THE SOFTWARE.
```

## punycode.js 2.3.1

License: MIT. Source: https://github.com/mathiasbynens/punycode.js.git



## uc.micro 2.1.0

License: MIT. Source: markdown-it/uc.micro

```
Copyright Mathias Bynens <https://mathiasbynens.be/>

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
