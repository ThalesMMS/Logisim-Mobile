# Frozen navigation census

| Invocation | Status | Phase | Taps | Screen queries | Model queries | Query seconds | Action seconds | First event s | Last event s |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1de489f6 | PASSED | presentation | 0 | 1 | 0 | 4.5762 | 0 | 0.0 | 4.5762 |
| 1de489f6 | PASSED | roots-open | 1 | 1 | 1 | 2.9031 | 0.0337 | 4.5763 | 7.5132 |
| 1de489f6 | PASSED | location-content | 1 | 1 | 0 | 2.7609 | 0.0665 | 7.5133 | 10.3407 |
| 1de489f6 | PASSED | download-content | 1 | 1 | 0 | 2.4473 | 0.0479 | 10.3408 | 12.836 |
| 4fa8a8c4 | PASSED | presentation | 0 | 1 | 0 | 5.2159 | 0 | 0.0 | 5.216 |
| 4fa8a8c4 | PASSED | roots-open | 1 | 1 | 0 | 2.8665 | 0.0286 | 5.216 | 8.1112 |
| 4fa8a8c4 | PASSED | location-content | 1 | 1 | 0 | 2.4429 | 0.098 | 8.1113 | 10.6523 |
| 569d50ca | PASSED | presentation | 0 | 1 | 0 | 3.7637 | 0 | 0.0 | 3.7637 |
| 569d50ca | PASSED | roots-open | 1 | 1 | 0 | 2.8371 | 0.0454 | 3.7638 | 6.6462 |
| 569d50ca | PASSED | location-content | 1 | 1 | 0 | 3.5813 | 0.0422 | 6.6463 | 10.2699 |
| 713a48c4 | FAILED | presentation | 0 | 1 | 0 | 3.6202 | 0 | 0.0 | 3.6203 |
| 713a48c4 | FAILED | roots-open | 1 | 1 | 0 | 2.8244 | 0.031 | 3.6203 | 6.4757 |
| 713a48c4 | FAILED | location-content | 1 | 13 | 0 | 27.537 | 0.0419 | 6.4758 | 36.5346 |

Query counts include failed attempts whose duration was recorded in finally. The final location phase has 12 completed reads followed by one timed-out attempt. The trace does not preserve the content of every read.

Final XML packages: com.google.android.documentsui.

Final XML contains 1 roots_list and 0 dir_list.

From `census-activity.txt`:

- `topResumedActivity=ActivityRecord{2abcd77 u0 com.google.android.documentsui/com.android.documentsui.picker.PickActivity t24}`
- `mCurrentFocus=Window{3c52626 u0 com.google.android.documentsui/com.android.documentsui.picker.PickActivity}`
- `mFocusedApp=ActivityRecord{2abcd77 u0 com.google.android.documentsui/com.android.documentsui.picker.PickActivity t24}`

From `census-window.txt`:

- `mCurrentFocus=Window{3c52626 u0 com.google.android.documentsui/com.android.documentsui.picker.PickActivity}`
- `mFocusedApp=ActivityRecord{2abcd77 u0 com.google.android.documentsui/com.android.documentsui.picker.PickActivity t24}`

From `census-documentsui-package.txt`:

- `Package [com.google.android.documentsui] (1616af3):`
- `versionCode=350820300 minSdk=29 targetSdk=35`
- `versionName=15`
