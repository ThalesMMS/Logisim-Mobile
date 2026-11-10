# Select Android I/O units

The I/O runner accepts repeated `--io-case` flags:

```sh
python3 scripts/e2e-android.py --serial emulator-5556 \
  --scenario io-showcase --language en \
  --io-case pointer-controls --io-case keyboard-tty --io-case dimensions \
  --artifacts /path/to/frozen-apks \
  --build-receipt /path/to/app-build-receipt.json \
  --output /path/to/new-evidence
```

| Unit | Actions |
| --- | --- |
| `signals` | Nineteen initial probes, displays and their properties |
| `dip-last` | DIP switch state, invalid size and save/reopen |
| `pointer-controls` | Held Button/keypad and joystick controls |
| `keyboard-tty` | Buffered input, control characters and capacity |
| `dimensions` | Component dimension edits and restoration |

Omitting `--io-case` selects all five units. Selection is validated before APK preflight or device access. After local APK checks, the initial manifest records the selected and unselected units before the first ADB call. Execution status belongs to `results.json` and `io-showcase-observations.json`, which record each unit that passed, failed or did not run.

A selected-unit run covers its declared units. The complete issue #44 acceptance also requires the remaining units and the visual reviews listed in the common contract.
