# Auditoria offline das raízes AX do XML, iOS44

Escopo: arquivos já capturados da rodada `ios-3a0a01f1-io44-b25-001`, teste `IoEndToEndTests/testIoDimensionChangesRemainExportable`. Nenhuma execução nativa, build ou alteração do projeto.

Base dos anexos: `/Users/thales/GitHub/logisim-mobile-worktrees/ios-final-e2e/outputs/ios-3a0a01f1-io44-b25-001/attachments/`.

## Árvore final e captura

`5A195C9B-A75E-461B-BEEB-ECB1B5446C57.txt`, timestamp UTC 2026-11-05 07:08:21.445, tem uma Application do pid76677 e três Window, todas834×1210.

- Window(Main), linhas4–68, contém o modal Project.circ. `editor-xml-body` está na linha26, frame(161,459,512,280); prévia identifica IO_Showcase e21971caracteres. Copy está na61. Existe exatamente um Button Close, linha65, frame(474,763,63,48), e seu filho StaticText na66. Não há segundo Button Close no arquivo.
- Window2, linhas69–308, contém editor antigo `visual_export_qualification`, título nas linhas83/88, canvas com EXPORT34_* e status78components/4wires na308. O Inspector antigo tem Label=EXPORT34_IMAGE na280. Não é um segundo editor IO nesta árvore.
- Window3, linhas309–311, contém apenas Other vazios.
- Não há Keyboard ou Key na árvore final. O TextView Label da linha280 pertence ao editor antigo, não ao XML. O bodyXML é Other com StaticText, não campo editável.

A captura `500B3F47-59CE-40F5-9576-1B6E8D0A4E4A.png` mostra o mesmo modal XML sobre o editor IO_Showcase com54components/64wires, sem teclado. O modal ocupa aproximadamente(137,387,560,448) pontos, consistente com AX. Portanto os pixels e o fundo AX divergem; não se pode atribuir os78/4 ao documento que foi copiado. Nenhuma etiqueta da árvore garante qual das janelas tem key/hit-testing.

## A divergência já existia antes do Receiver

`EA4CC715-4B26-4C5E-AC52-6A2E5F4C7E7E.txt`, UTC07:07:41.352, capturado durante a importação IO antes do Receiver:

- Window(Main) report, linhas4–57. Origem UUIDIO correta na28: `io-showcase-0CC34CEE-65CC-4467-8BDC-30F865D372C8.circ`. CircuitIO nas30/34etc. Close único na56, frame(610,873,63,48).
- Window2 começa58; o título `visual_export_qualification` já está72/77, áreaCanvas178 e status78/4 na297.
- Window3 vazia começa298.

Logo a árvore de fundo antigo não foi criada exclusivamente pela ida/volta ao ClipboardReceiver. A captura inicial `76033A37-0A6E-45EC-A116-DACE29430C58.txt`, UTC07:07:34.160, tem duasWindow, sendo modal de report antigo e janela vazia, sem editor de fundo exposto. Não há captura que fixe a origem interna da Window2.

## Intervalo da tentativa Close

O anexo de evento `69F75554-83DC-4EA3-8AB7-7A7D7C6EF799`, UTC07:08:09.682, é NSKeyedArchive plist. Seu XCSynthesizedEventRecord é tap destinado ao pid76677, parentWindow834×1210. Os dois XCPointerEvent registram(492.2981671466551,703.1480597029523), DOWN eUP separados0.05s.

Isso é coordenada sintetizada, não prova de entrega ao handler. Ela fica acima do frame Close final, mas o frame final é cerca12s posterior; a captura/vídeo do instante do toque ainda é necessária para comparar geometria no momento da ação. Atribuir falha dehandler com base só no nome `Tap Close` do XCTest seria incorreto.

`C5B0FAD9-5299-4AE0-BD19-08A6CD1D3DB8`, UTC07:08:09.381, o snapshot imediatamente anterior ao evento, contém somente Application frame834×1210, children=[], enabled=true, hasKeyboardFocus=false, windowContextID490994061. Não inclui Close, XML ou nenhuma Window.

Os dez Debug description anexados durante a espera, UTC07:08:10.834 até07:08:19.827, também contêm apenas Application sem Window e semXML. São `00146999`, `0CBEB3B3`, `DDD8CBCA`, `984059F0`, `4AA26D67`, `B34CD282`, `FA507047`, `9753EB4B`, `4767281D`, `FD3D9346`. Cada arquivo tem378caracteres. Portanto o intervalo de timeout não é uma observação AX repetida deXML ainda aberto. A captura final prova que ele continuou aberto depois; o caminho da consulta durante a espera também estava degradado/vazio.

## Conclusão limitada

Há divergência concreta de namespace/árvore AX e pixels, anterior aoReceiver, e snapshotsAX vazios no instante do toque/espera. O modal eXMLcopiado são IO reais, porém o teste não estabeleceu de forma auditável uma raiz única do documento atual para interações e observações. Não foi possível atribuir a causa a produto, Compose, XCTest ou cena/janela apenas com estes anexos. Recomenda-se correlacionar o evento sintetizado com os frames do vídeo no mesmoPTS e exigir um contexto AX atual/consistente antes de repetir o fluxo; não aumentar timeout nem afrouxar oráculos.
