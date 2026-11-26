---
name: android-device-control
description: Operate the Android screen in Codex Mobile. Use for opening apps, inspecting screenshots, tapping, entering text, and verifying visible results on an authorized device.
---

# Controle do Android

Use as ferramentas que esta conversa realmente oferece. Examine o esquema ativo antes de chamar uma ação nova; chats antigos podem ter uma lista diferente. Use a sessão de controle autorizada e respeite as confirmações exigidas pelo app.

## Abrir e observar

- Tente `open_app` com o pacote exato. Se falhar e o Shizuku estiver conectado, confirme qual pacote instalado corresponde ao app, resolva a atividade de launcher com o gerenciador de pacotes do Android e abra o componente confirmado. Não chute a atividade nem abra outro app pelo nome parecido.
- Depois de abrir, tocar ou mudar o foco, dê tempo para a transição terminar. Se a primeira captura ainda mostrar o estado anterior, observe de novo após uma pausa curta. Se houver `watch_region`, use uma área estável e espera limitada quando houver uma mudança visual bem definida.
- Uma captura bem-sucedida só vale como evidência visual se a imagem chegou de fato. Se aparecer apenas Base64 truncado, tente outra captura com saída menor, quando disponível. Não invente o conteúdo nem use o código de saída como prova do que apareceu.

## Tocar e digitar

- Use coordenadas da tela física. Se o recorte começa em `(x, y)`, mede `w × h` e chegou como `ow × oh`, converta o ponto `(u, v)` da imagem para `(x + u*w/ow, y + v*h/oh)`. Meça na área da imagem, descontando margens da prévia. Confirme a orientação e mire no centro do alvo.
- Um rótulo de voz ao lado do compositor não prova que só existe entrada por voz. Toque no campo, espere e confirme foco ou teclado antes de inserir texto. Use `insert_text` para preservar acentos e Unicode. Se a ação não existir, use o teclado visível ou, para texto compatível e de escopo claro, o `input text` via Shizuku com argumentos corretamente escapados. Confira o texto no campo antes de enviar.

## Concluir

- Confira app, destinatário ou conversa e conteúdo antes de uma ação externa. Se a confirmação exigida já foi dada para o mesmo destino e texto, prossiga sem pedir de novo. Mudanças relevantes exigem nova confirmação.
- Após enviar, procure a mensagem no histórico ou outro sinal inequívoco. Se o resultado estiver incerto, verifique antes de tentar novamente para evitar duplicatas.
- Em caso de falha recuperável, tente a alternativa adequada e verifique o resultado. Se faltar autorização, imagem utilizável ou uma forma compatível de interagir, informe a etapa exata que ficou bloqueada.
