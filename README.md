# Codex Mobile

App Android ARM64 com Kotlin, Jetpack Compose e Material 3. O APK traz Ubuntu Base, PRoot, Node.js, Codex CLI e o armazenamento de conversas em Rust. A distribuição e as credenciais ficam no armazenamento privado do app. O Termux instalado é dispensável para executar o APK.

No primeiro uso, siga **Bem-vindo**, **Permissões**, **Login** e **Usar Codex**. A permissão de arquivos compartilhados dá ao Bash acesso às pastas do Android e habilita o destino padrão `/sdcard/codex-android/arquivos do chat`. O modo privado usa `/root/Projetos` dentro do Ubuntu embutido. Na primeira instalação, um mini shell mostra o arquivo em extração e o progresso medido de cada pacote. O aplicativo abre o login do Codex no navegador do sistema.

O botão **Uso** mostra porcentagens restantes de cada janela que o Codex relata. A troca de modelo e esforço fica junto ao compositor. O botão **+** abre o menu do explorador integrado. As execuções do Bash aparecem recolhidas e mostram comando, saída e código de saída ao expandir.

## Transporte do Codex

No Ubuntu integrado, o binário Rust supervisiona o Codex App Server e encaminha os quadros JSON-RPC entre o serviço e o Codex. O mesmo binário grava o histórico e mantém um índice leve para abrir o app sem ler as mensagens das conversas. Na primeira abertura após a atualização, os históricos antigos são indexados uma vez. O servidor Node.js ainda atende o Android e mantém a lógica de sessões, aprovações e arquivos.

Para testar o transporte localmente, execute `cargo test --locked --manifest-path runtime/Cargo.toml`.

## Versões e instalação

Há dois APKs ARM64, ambos com o mesmo nome de pacote e a mesma assinatura. Escolha **um** deles:

- **Padrão** (`Codex-Mobile-2.1.16-padrao-arm64.apk`): não declara o serviço de acessibilidade e não oferece o controle da tela.
- **Com controle** (`Codex-Mobile-2.1.16-controle-arm64.apk`): inclui o controle por acessibilidade, com confirmação do bloqueio do dispositivo, indicador visual durante a execução e encerramento pelo chat.

Os dois atualizam a mesma instalação quando assinados com a mesma chave. O suporte começa no Android 11. As capturas do controle são solicitadas pelo Codex durante a tarefa; não são uma transmissão contínua de vídeo.

### Instalar a versão com controle pelo Shizuku

O Shizuku e o Termux são necessários apenas para este método de instalação. Inicie o Shizuku via depuração sem fio ou ADB, autorize o Termux no Shizuku e, no aplicativo Shizuku, exporte os arquivos `rish` e `rish_shizuku.dex` para o diretório privado `~/rish` do Termux. No Android 14 ou posterior, deixe o DEX somente de leitura. Com o APK baixado em `Download`, no Termux execute:

```sh
chmod 400 ~/rish/rish_shizuku.dex
chmod +x ~/rish/rish
export RISH_APPLICATION_ID=com.termux
~/rish/rish -c 'id'
~/rish/rish -c 'if [ -e /data/local/tmp/codex-mobile-2.1.16-controle-arm64.apk ]; then echo Arquivo-temporario-ja-existe; exit 1; fi; cp /sdcard/Download/Codex-Mobile-2.1.16-controle-arm64.apk /data/local/tmp/codex-mobile-2.1.16-controle-arm64.apk && pm install -r /data/local/tmp/codex-mobile-2.1.16-controle-arm64.apk'
```

Confirme o pedido de instalação no aparelho se aparecer. A cópia em `/data/local/tmp` permite que o instalador Android leia o arquivo; se ela já existir, confira que é a versão desejada antes de repetir o comando. `Success` indica que o instalador aceitou o APK. O método não garante contornar bloqueios do sistema ou do Play Protect; se aparecer `INSTALL_FAILED_USER_RESTRICTED`, confira o pedido exibido e as opções de instalação por depuração do aparelho. Nunca é necessário desinstalar a versão anterior para atualizar com a mesma assinatura.

Depois, solicite o controle em uma conversa. Ative **Controle do Codex Mobile** em Acessibilidade e confirme o bloqueio do aparelho para liberar aquela sessão. A acessibilidade deve ser desativada nas configurações do Android se você quiser remover a permissão do sistema, além de encerrar a sessão no app.

## Build

Use JDK 17, Gradle 8.10.2, Android SDK 35 e as dependências do runtime descritas nos scripts do projeto. Em um host Linux ARM64, forneça um `aapt2` correspondente à arquitetura. O armazenamento de conversas exige Rust com o alvo `aarch64-unknown-linux-gnu`.

```sh
./build-native.sh :app:assembleStandardRelease :app:assembleControlRelease :app:testStandardReleaseUnitTest :app:testControlReleaseUnitTest
```

Os APKs ficam em `app/build/outputs/apk/standard/release/` e `app/build/outputs/apk/control/release/`. O caminho da chave pode ser configurado com `CODEX_MOBILE_KEYSTORE`. Para atualizar um APK instalado, use a mesma chave.

As listas, folhas modais, animações e superfícies usam Compose e Material 3. Os ícones da interface são Lucide, com a licença incluída no APK. CommonMark interpreta Markdown antes da renderização nativa. O backend responde apenas no loopback com um token efêmero.

## Ferramentas Android do Codex

O Ubuntu via PRoot é o ambiente padrão. Em conversas novas, o Codex também pode solicitar `termux_shell` ou `adb_shell` quando uma tarefa realmente exige aquele ambiente. No modo YOLO, comandos ADB usam o Shizuku autorizado sem confirmação por comando; fora desse modo, cada chamada pede aprovação.

- **Termux**. Em Configurações, toque em **Conectar Termux**. O Termux precisa autorizar comandos externos com `allow-external-apps=true` em `~/.termux/termux.properties`.
- **Shizuku**. Inicie o serviço com Depuração sem fio ou ADB e toque em **Conectar Shizuku**. A ferramenta `adb_shell` recebe diretamente a linha para o shell Android, sem o prefixo `adb shell`.

No Ubuntu, o mini shell mostra as últimas linhas enquanto o comando roda. Termux e Shizuku entregam o resultado ao fim da execução. Abra a linha do comando para examinar e copiar a saída registrada.

## Cofre de credenciais

Abra **Menu → Credenciais**, crie uma senha para o cofre e adicione um **Token**, um **Login** ou uma credencial **SSH**. Os nomes, descrições e dados ficam cifrados em armazenamento privado com AES-GCM e uma chave derivada da senha por PBKDF2-HMAC-SHA256.

Em uma conversa nova, peça ao Codex para usar a credencial pelo nome ou digite `+nome` no compositor. O autocomplete fica disponível com o cofre aberto. A primeira solicitação pede a senha e libera o uso das credenciais naquela conversa até bloquear o cofre. **Bloquear cofre** revoga a autorização em memória.

A ferramenta `credential_shell` pode autenticar uma solicitação HTTPS ou criar um arquivo novo com um token, conforme o destino aprovado no app. O valor não aparece na resposta da ferramenta; um arquivo criado contém o segredo e fica acessível aos processos que tiverem permissão de lê-lo.
