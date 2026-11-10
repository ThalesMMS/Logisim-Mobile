# Falha de fechamento do XML na rodada iOS44

A falha terminal continua FAIL. O XML foi copiado corretamente, mas o registro disponível não demonstra que o toque atingiu o botão Close visível. Há uma inconsistência concreta de cena/janela e coordenadas na observação nativa. Não há prova de falha do handler Close do produto.

## Escopo e origem

Revisão somente dos arquivos existentes de `ios-3a0a01f1-io44-b25-001`, teste `IoEndToEndTests/testIoDimensionChangesRemainExportable`. Não houve UI, simctl, build, Gradle, nova execução ou edição do projeto.

- Base: `/Users/thales/GitHub/logisim-mobile-worktrees/ios-final-e2e/outputs/ios-3a0a01f1-io44-b25-001`.
- Produto declarado e instalado segundo `results.json`: `3a0a01f1af05dd3fea75d5a1499c762d039c478a`, bundle SHA256 `813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef`.
- Fontes do teste lidas da cópia congelada `harness/`; fontes do produto lidas por `git show 3a0a01f1:<path>` e preservadas em `/tmp/logisim-io44-xml-close-review/`.
- Revisão independente das árvores: `/tmp/logisim-ios44-xml-ax-roots.md`.

## O que ocorreu

| Etapa | Relógio do teste | Recibo |
|---|---:|---|
| Event público | 16.44 s | `test.log:95–103` |
| Baseline conhecido | 18.37–19.24 s | Os 19 probes literais aparecem corretos em `test.log:105–128` e `4F609B0E-156B-4071-B95F-6DF5536AF64F.txt` |
| Abre XML | 19.90 s | `.circ`, `test.log:137–145` |
| Copy | 22.71 s | `test.log:166–180`; evento `15A50BA9-2FFD-4773-9370-A04ECD68AC51` em (202.75,715.0) |
| Receiver cola e lê XML | 24.13–37.24 s | `test.log:181–239`, paste nativo e limpeza |
| Retorno ao app | 38.33–38.41 s | `app.activate()`, espera apenas `.runningForeground`, `test.log:240–244` |
| Tap Close | 38.64 s; síntese 40.42 s | `test.log:250–256`; evento `69F75554-83DC-4EA3-8AB7-7A7D7C6EF799` |
| Espera de desaparecimento | 41.74–50.76 s | `test.log:257–296`, snapshots AX vazios |
| Falha | 51.14 s | `ObservedProjectXML.swift:58`, `test.log:297` |
| Captura final | 52.48–52.58 s | modal ainda aberto, `500B3F47...png` e `5A195C9B...txt` |

A exportação `EB379FD8-8FF7-4584-88DE-E1155FD198B8.xml`, o XML recebido `7FE424D3-DB61-42D5-B74E-BE02C9DC591B.circ` e `documents/io-showcase.circ` são byte a byte idênticos: 21.971 bytes, SHA256 `a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9`. São 54 componentes e 64 fios, main IO_Showcase. Isso prova a leitura completa real deste documento, não o fechamento nem os passos dimensionais seguintes. O teste parou no primeiro checkpoint, antes de editar dimensões; não produziu os 12 checkpoints pretendidos.

## Janela, papel, rótulo e geometria

Árvore final `5A195C9B-A75E-461B-BEEB-ECB1B5446C57.txt`, UTC 07:08:21.445:

- Uma Application, pid 76677, frame 834×1210 pontos.
- Window(Main), linhas 4–68, contém o modal XML IO. Título StaticText `Project .circ`, linha 25. Body `Other`, identifier `editor-xml-body`, frame (161,459,512,280), linha 26.
- Existe um único `Button Close`, sem identifier, linha 65, frame (474,763,63,48). O filho StaticText também tem label Close, linha 66. Centro calculado do frame, apenas candidato, é (505.5,787).
- Copy e Paste estão nas linhas 61/63, y691–739. XML grande aparece como StaticText, sem campo editável. Nenhum Keyboard ou foco de teclado é exposto.
- Window 2, linhas 69–308, expõe o editor antigo `visual_export_qualification`, título nas linhas 83/88, valores EXPORT34 e 78 componentes/4 fios na linha 308.
- Window 3, linhas 309–311, vazia.

Os pixels da captura final mostram IO_Showcase com 54/64. Portanto o fundo AX de 78/4 não pode ser usado como documento que foi exportado. Nenhuma etiqueta do dump estabelece qual cena recebeu hit testing.

A janela antiga já aparece no report IO anterior ao Receiver, `EA4CC715-4B26-4C5E-AC52-6A2E5F4C7E7E.txt`, UTC 07:07:41.352. Window(Main) contém o report da origem UUID IO correta, Close na linha 56. Window 2 começa na linha 58 e já expõe visual_export_qualification. A ida ao Receiver não explica sozinha a presença da outra raiz.

## Evento sintetizado e vídeo

O anexo de evento `69F75554-83DC-4EA3-8AB7-7A7D7C6EF799` é um plist NSKeyedArchive, decodificado com `plistlib`, sem consultar o simulador. A estrutura registra:

- `targetProcessID=76677`, `name=tap`, `parentWindowSize={Width:834,Height:1210}`.
- Ambos os eventos de ponteiro usam `(492.29816714665515,703.1480597029523)`, offsets 0 e 0.05 s.
- Timestamp do manifesto: UTC 07:08:09.682.

Isso é a coordenada sintetizada pelo XCTest, não confirmação de entrega ao app. Ela está fora do frame Close final e aproximadamente 84 pontos acima do seu centro. Fica no espaço vazio à direita de Copy/Paste dentro do body XML.

Para não comparar apenas com um frame final 12 segundos posterior, foram extraídos frames do vídeo do próprio testcase, `CD9D02E9-74A7-4661-83FF-9F625DCB1809.mp4`, sem alterar o original. A lista de frames e PTS está em `/tmp/logisim-io44-xml-close-review/video-frames.json`.

- PTS 38.905, 40.396667, 40.701667 e 41.063333 mostram o mesmo modal IO. Close continua na região inferior próxima ao frame AX final, não na coordenada sintetizada y703.
- PTS 38.905 e 40.396667 mostram overlay nativo `9 Hidden Windows / Show All Windows` após a ativação. No PTS 38.905 a janela ainda aparece animada/inset; a partir de 40.396667 o desenho ocupa o frame final.
- O evento UTC do manifesto está 40.685 s após o timestamp do vídeo no manifesto; isso ajuda a localizar o intervalo, mas não qualifica uma associação metrológica entre UTC e PTS. Os frames são evidência visual do intervalo de retorno/tentativa, não prova de latência ou evento recebido.
- A captura final está em 1668×2420 pixels, exatamente 2× o frame lógico. Nenhuma coordenada de toque foi aplicada por esta revisão.

O snapshot de evento imediatamente anterior, `C5B0FAD9-5299-4AE0-BD19-08A6CD1D3DB8`, UTC 07:08:09.381, contém apenas Application com children vazios, frame 834×1210, hasKeyboardFocus=false e windowContextID490994061. Os dez dumps durante a espera, de 07:08:10.834 a 07:08:19.827, também só têm Application, sem Window nem XML. Não são dez observações positivas de XML persistente. A captura final prova que o modal continuou aberto; o caminho de snapshots durante a espera estava degradado.

## Fontes e interpretação

`harness/ObservedProjectXML.swift:53–58` chama `tapKey(action_close)` global e espera ausência do primeiro título Project.circ global. `NativeUIHarness.swift:58–90` procura descendentes da Application, aceita primeiro elemento com geometria em tela e isHittable; não associa a cena ao documento atual. `tapKey`, linha 208, usa `element.tap()`, sem escolher um ponto de frame explicitamente.

`harness/ClipboardReceiverUI.swift:13–17` termina o Receiver e ativa o processo Logisim, esperando apenas estado runningForeground. Não valida que uma única cena, o documento esperado, a raiz AX e os pixels estão consistentes após o retorno.

No produto 3a0a01f1, `iosApp/LogisimMobileApp.swift:7–20` usa WindowGroup e DocumentWindow com estado de entrada e SceneStorage próprios. Cada Coordinator cria seu IosDocumentSession. O Info.plist do artefato aprovado confirma `UIApplicationSceneManifest.UIApplicationSupportsMultipleScenes=true`, salvo em scene-manifest.json. O vídeo confirma que o sistema já tinha múltiplas janelas. Isso sustenta tratar a seleção/ativação de cena como precondição real do teste. Não prova, por si, que múltiplas cenas sejam um defeito do produto.

No mesmo produto, `App.kt:571–578` define Close como `importLoader.cancel(); xmlDialog=false`, e `XmlDialog`, linhas 845–869, liga o dismissButton diretamente a esse callback. A fonte não contém um gate de carregamento para Close. A revisão não demonstrou que esse callback foi invocado nesta tentativa.

O status Operation cancelled já aparece antes do toque no vídeo e na árvore de fundo. A fonte o publica em CanvasCommand.CANCEL. Não serve como recibo de que Close executou.

## Próximo passo limitado

A próxima execução precisa primeiro qualificar o contexto de janela após o retorno do Receiver e registrar, imediatamente antes do toque, cena/raiz, frame real do modal, Button Close único, screenshot e coordenada pretendida. Se for necessário experimentar um toque no centro do frame observado, isso será uma nova prova de entrega da automação, sem reclassificar esta rodada como PASS. A espera de fechamento deve depender de uma raiz válida; uma Application vazia não pode provar ausência do modal.

Não aumentar timeout, descartar o relatório atual, afrouxar os 19 probes ou modificar o produto para contornar o roteiro. A comparação literal dos outros três casos permanece com o owner. Nenhuma sequência de I/O completa foi aprovada por esta análise.
