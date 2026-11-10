# Revisão independente dos parsers

PASS no escopo das fixtures observadas. Não encontrei troca de endianness, magic ou offsets nos dados atuais. A consulta inicial da seção DER usava um nome inválido; a versão atual do script e de `sections.json` já usa `__ents_der`.

Revisão read-only dos binários e dos outputs existentes, com verificações independentes em Python puro. Não executei os scripts originais, `codesign`, `openssl`, REA, builds, instalação, assinatura ou UI. A única escrita desta revisão é este parecer.

## Assinatura Mach-O

Conferi os quatro executáveis principais diretamente nos arquivos apontados por `signature-slots.json`. Todos têm Mach-O 64 little-endian thin, CPU `0x0100000c`, e apenas um `LC_CODE_SIGNATURE` de 16 bytes. Os pares produzido/instalado são exatamente iguais byte a byte.

| Executável | Bytes | Offset da assinatura | Bytes LC_CODE_SIGNATURE | Comprimento SuperBlob | Offset absoluto slot 5 | Offset absoluto slot 7 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| app, produzido e instalado | 40688 | 39696 | 992 | 975 | 40215 | 40551 |
| provider, produzido e instalado | 38080 | 37088 | 992 | 984 | 37616 | 37952 |

O SuperBlob é big-endian, magic `0xfade0cc0`. Os cinco slots são `0`, `2`, `5`, `7` e `65536`, com offsets relativos ao início do SuperBlob, comprimentos dentro do limite declarado e sem sobreposição. As extrações existentes correspondem exatamente aos bytes originais. O slot 5 tem magic `0xfade7171`, 336 bytes com payload XML de 328 bytes. O slot 7 tem magic `0xfade7172`, 112 bytes com payload DER de 104 bytes.

Cada CodeDirectory tem magic `0xfade0c02`, versão `0x20400`, SHA-256 de 32 bytes, sete special slots e três code slots. Recalculei os hashes dos blobs completos dos slots 2, 5 e 7, incluindo seus headers de 8 bytes. Eles correspondem aos digests negativos do CodeDirectory. Os três hashes de páginas de 16384 bytes também correspondem, incluindo a última página parcial. Os limites são `[0,16384)`, `[16384,32768)` e `[32768,39696)` no app ou `[32768,37088)` no provider. `codeLimit` coincide com o offset da assinatura, `scatterOffset` é zero e `codeLimit64` é zero. Os SHA-256, tamanhos, offsets e digests registrados no JSON conferem.

## XML e DER

Uma segunda revisão independente conferiu os quatro DER de 104 bytes, idênticos e iguais aos arquivos `codesign-der.stdout`. O SHA-256 do payload é `72c71bbb63d33e2341a304f97b90adb1983485cfc99e4144067dcd4b8b06652e`.

Os sete nós da árvore registrada conferem em tags, offsets, comprimentos, limites dos pais e SHA-256. A forma observada é application tag `0x70`, INTEGER 1, dicionário `0xb0`, entrada SEQUENCE com chave UTF8 e array SEQUENCE de strings UTF8. Os XML de slot, com 328 bytes, e os XML extraídos por `codesign`, com 316 bytes, representam o mesmo plist em todos os casos. XML e DER representam somente `com.apple.security.application-groups` com o array `['group.thalesmms.logisim.validation.revocationfixture']`.

O script e `format-comparison.json` declaram corretamente o limite do decoder à forma v1 observada de dicionário com arrays de strings. Isto não é uma validação DER geral.

## Seções

Reparseei os oito arquivos, executáveis e debug dylibs, e comparei todas as entradas com `sections.json`. O layout `segment_command_64` de 72 bytes, o início de cada `section_64`, seu tamanho de 80 bytes, `addr`, `size`, `offset`, flags e hashes conferem. Os limites dos segmentos e seções também conferem. Os tipos zero-fill `1`, `12` e `18` usam a interpretação correta, sem tentar ler bytes do arquivo.

As contagens são 14 e 34 seções no app, 13 e 33 no provider, tanto no produzido quanto no instalado. Nenhum desses oito arquivos contém `__entitlements`, `__info_plist` ou `__ents_der`, em `__TEXT` ou em outro segmento.

Finding corrigido durante a revisão: `__entitlements_DER` tem 18 bytes e não cabe no campo `sectname[16]`. A busca inicial por esse literal não provaria a ausência da seção DER pretendida. Conferi a versão corrigida com `__ents_der` em `compare-formats-and-sections.py` e em todos os `absentRequestedSections` atuais. O inventário completo e a verificação independente confirmam a ausência de `__ents_der`.

## Limites

O resultado cobre estas fixtures thin arm64, CodeDirectory v0x20400 com SHA-256, páginas de 16 KiB sem scatter, e DER v1 nesta forma específica. Não amplia a cobertura dos scripts para Mach-O fat, outro endianness, CodeDirectory com scatter ou `codeLimit64` efetivo, outros algoritmos ou formas de entitlement. Não validei os special slots externos 1 e 3, a cadeia de certificados, CMS, política de confiança, aceitação em runtime nem o conteúdo consumido por CoreSimulatorBridge. Os dados não estabelecem causa do erro investigado.

Versões revisadas por SHA-256:

- `inspect-signature.py`: `0375a83a9356f12900e6079e2e889d24ece6f34608fadb9aa11c1cf0941c760b`.
- `compare-formats-and-sections.py`, após correção do nome: `495086718175f4e5f315f25ecac45ae4c3507a6d750797a97dfb3625e16eb330`.
- `signature-slots.json`: `4cf401062fc7a5837df55ea232816bc94a31364ef173cb82783868533100b1c1`.
- `format-comparison.json`: `4c1c6b79a3d9969b714e87900c4cd3d3d3e0f1830f64132ee8d9134b28ea6605`.
- `sections.json`, após correção do nome: `a985141bcfbd9b6249107f9242992b7bf8e026cd88db8def8a19a56bbc5dd587`.
