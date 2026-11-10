# Native text selection evidence

The rejected premise was that a focused tool text field reliably exposes the
native editing menu after a long press. Saved native runs do not support that
assumption across these candidates. This census reads existing logs and
accessibility attachments; it does not launch an app or change text.

| Saved run | Resolved native Select All taps | Selection-menu failures | Last focused field |
| --- | ---: | ---: | --- |
| eb376-002 | 2 | 0 | HDL source |
| e7-hide-001 | 1 | 1 | HDL source |
| a046-ime-001 | 0 | 0 | HDL source |
| b90-full-001 | 0 | 0 | Companion address |
| b90-full-002 | 0 | 2 | Companion address |

A resolved tap requires a native `Select All` MenuItem target followed by a
synthesized event. Predicate and typing log lines are not counted. Earlier
runs captured their final trees after successful editing actions, so their
zero-menu final snapshots do not contradict the recorded menu taps.

The two b90-full-002 failure snapshots contain a focused address field and no
MenuItem, Select All, Cut or Copy actor. Their screenshots also show no editing
menu. This is an observed difference between attempts, not proof that a
particular app change caused it. Abbreviated accessibility values are not used
to infer full-value equality.

Every attempt remains failed and unqualified for the complete HDL workflows.
The b90 attempts did not reach landscape HDL editing or compiler execution.
The independent geometry case is alpha-only and also cannot qualify #58/#59.

Rebuild the census from the preserved local outputs:

```sh
python3 scripts/ios_hdl_ui/text_selection_census.py \
  --root /absolute/path/to/checkout-with-preserved-outputs \
  --output /absolute/path/to/census.json
```

The JSON records producer pins and SHA-256 hashes for the script and input
receipts, logs, manifests and trees. It emits no field values or typed tokens.
