# Transactional workspace attachments

`WorkspaceAttachment` copies binary input and returns copies from its `bytes`
property. `WorkspaceDocument.attachments` maps a caller-owned key, such as
`portable-source`, to a descriptor containing the original name, MIME type,
byte count, and SHA-256. At most 32 entries and 64 MiB total are accepted.

Attachments are not embedded in circuit XML. The repository stores binary data
in a separate canonical Base64 envelope named by its SHA-256. Equal bytes can
share a blob while each project retains its own file metadata.

Creation writes every new blob before the immutable project payload, then
replaces the catalog backup and catalog. The catalog remains the commit point.
Failed operations may leave unreferenced blobs or payloads; they are never
selected as committed projects. Existing corrupt blobs are not overwritten.

Project payload version 3 includes attachment descriptors in its checksum.
Projects without attachments still write version 2. Versions 1 and 2 remain
readable; catalog format version 1 is unchanged.

Missing, corrupt, or unreadable attachment data leaves the selected revision's
XML intact and opens that document read-only with a localized notice. Blob
failures do not cause a fallback to a different revision or source. Normal
edits and metadata/history operations retain descriptors. Duplication retains
the same source or fails explicitly if its bytes are unavailable.

`WorkspaceSession.create` forwards explicitly supplied attachments. The
single-argument `saveLocalAsCopy` preserves the current buffer's attachments;
the overload accepting explicit XML uses only explicitly supplied attachments.
This prevents a draft for project B from inheriting project A's source.

Verification used Kotlin compiler/JUnit directly against an isolated reference
jar and source snapshot, without Gradle: 45 workspace/attachment tests passed.
Common-source formatting, portability checks, and ten localization script tests
also passed. Native adapters and UI attachment handling are verified separately.
