# Android circuit share delivery

The mounted editor shared its current SAP-1 project through the native chooser to an external test receiver on the API 35 emulator. The receiver read all 52,746 bytes through the restricted `content://thalesmms.logisim.mobile.e2e.files/circuits/` route and parsed seven circuits. Its SHA-256 was `259d61bc36d2ad2b837df948eefca5f55adca2b7b3f0fc31fd41bbd88a16e298`, matching the full XML copied from the editor before sharing. The editor returned with identical XML.

[The receipt](android-share-df276e.json) records the receiver's actual URI, MIME metadata, size and digest, source snapshots, and unchanged installed APK digests. The product remains `df276e39`, app SHA-256 `600f656ac6cbb4661bbff880995b77a1fc240bb0607671bb96d0a010b234a834`.

This targeted delivery passed independently. The earlier file workflow remains failed at its recorded HWUI ANR; the successful controlled chooser/cancel case is also separate. No cloud recipient, email account, messaging service or printer was used.
