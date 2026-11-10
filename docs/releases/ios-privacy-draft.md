# iOS privacy policy draft

Draft dated 4 November 2026 for Logisim Mobile, bundle `thalesmms.logisim.mobile`. This document is not a published policy. The release owner must confirm the final application, companion configuration, contact address, and public policy URL before submission.

Logisim Mobile stores circuit projects, editor preferences, and references to documents on the device. Projects contain the content the user creates or imports. Files selected through Apple's document picker can remain with the selected file provider. The provider and the device's backup settings control copies outside the application's storage.

The editor and built-in examples do not require an account. A user can choose a companion host for desktop tool actions. After explicit authorization, those actions send their selected inputs and parameters to that host and receive results. The companion's operator and retention settings control copies on that host. Do not send private project content to a host you do not trust.

Sharing and exporting use the destination or service selected by the user. Those destinations may retain their own copies. Removing the application removes its local application data, subject to device backup behavior. It does not delete documents already exported, copies held by a file provider, or results stored by a companion host.

The required API manifest explains use of application preferences, file metadata, and elapsed-time measurements. This manifest is distinct from the App Privacy answers in App Store Connect. The release owner must reconcile those answers with the final linked SDKs and actual network behavior. This draft does not claim that an uninspected distribution binary collects no data.

A published version needs a support and privacy contact, a verified retention and deletion procedure for any operator-managed service, and a link accessible from the app and App Store Connect. No contact or service operator has been invented for this draft.
