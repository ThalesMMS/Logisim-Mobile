# Observed keyboard control labels

The retained Android `3a` keyboard attempt passed `AB` retention and the connected A, B and empty-buffer probes, then failed before clicking ASCII backspace. The two final trees show a fully visible backspace label at `[518,1158][795,1211]`. Its enabled clickable button extends to y1248, while the observed horizontal scroll viewport ends at y1247. The shared full-control visibility predicate therefore rejects the button. This replay explains rejection in those retained trees; it does not reconstruct every poll in the preceding twenty seconds.

The dedicated keyboard-control helper keeps the semantic button and its touch point separate. It requires one exact app-owned control label, an enabled button below the observed Inspector scroll row, valid ownership and a fully visible label strictly inside the row. It taps the original label node at the center of its measured rectangle. It never changes node metadata or weakens the global visibility predicate.

ASCII form feed is a separate known case. Its retained label and owner both end exactly at the row's right boundary, and the image shows the text cut off there. Bounds returned by Android can already be clipped, so touching that boundary does not prove the complete label is visible. The helper can request one horizontal swipe inside the observed `HorizontalScrollView`, followed by a new tree with an interior label. It does not tap a partial label, guess the hidden label's dimensions, scroll vertically, or repeat the navigation blindly.

Each contracted control keeps one twenty-second deadline, including observation, the optional swipe and tap. Time is checked again after writing observation artifacts and before dispatch. A late SDK return cannot authorize a new input after the deadline. Capture and protocol errors propagate to the existing client policy; this helper adds no capture retry or scenario retry.

The existing backspace and form-feed actions now use this helper. It remains outside Home/End and all other controls. Fixture bytes, nineteen baseline probes, `AB`, TTY expectations, ASCII codes, clock steps and capacity expectations are unchanged. The public input driver is unchanged. The new helper is retained in the execution manifest's source inventory. Before either input, the already observed tree and selected geometry are saved. `commandCompleted` records command return, not proof that the application consumed the event; the existing connected-probe assertions still establish the resulting behavior.

The regression fixture is the unmodified final tree from the failed attempt. The tests reproduce the former no-match result and validate the observed label point without accepting a disabled, hidden, foreign, ambiguous, malformed or partially visible control. The form-feed next-tree fixture is a hypothetical test observation, not a predicted coordinate or evidence from a native retry.

Run the focused replay without Android:

```sh
python3 -B -m unittest discover -s scripts -p test_android_keyboard_controls.py -v
```

Seventeen focused tests and 87 related Python checks passed. These source and fake-driver checks do not qualify a native rerun. No build, ADB command or device interaction was executed during this correction. Product code and the input-event injection implementation are unchanged.
