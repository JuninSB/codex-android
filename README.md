# Codex Mobile

App Android ARM64 com Kotlin, Jetpack Compose e Material 3. O APK traz Ubuntu Base, PRoot, Node.js, Codex CLI e o armazenamento de conversas em Rust. A distribuição e as credenciais ficam no armazenamento privado do app. O Termux instalado é dispensável para executar o APK.

No primeiro uso, siga **Bem-vindo**, **Permissões**, **Login** e **Usar Codex**. A permissão de arquivos compartilhados dá ao Bash acesso às pastas do Android e habilita o destino padrão `/sdcard/codex-android/arquivos do chat`. O modo privado usa `/root/Projetos` dentro do Ubuntu embutido. Na primeira instalação, um mini shell mostra o arquivo em extração e o progresso medido de cada pacote. O aplicativo abre o login do Codex no navegador do sistema.

O botão **Uso** mostra porcentagens restantes de cada janela que o Codex relata. A troca de modelo e esforço fica junto ao compositor. O botão **+** abre o menu do explorador integrado. As execuções do Bash aparecem recolhidas e mostram comando, saída e código de saída ao expandir.

## Build

Use JDK 17, Gradle 8.10.2, Android SDK 35 com Build Tools 34.0.0 e as ferramentas `curl`, `tar`, `dpkg-deb`, `sha256sum` e `patchelf` (ou Node.js para aplicar a alteração de dependência ELF). Em um host Linux ARM64, forneça um `aapt2` correspondente à arquitetura. O armazenamento de conversas exige `rustc` com o alvo `aarch64-unknown-linux-gnu`. Em compilação cruzada, use `aarch64-linux-gnu-gcc` ou configure `CODEX_CHATSTORE_LINKER`.

```sh
./build-native.sh :app:testReleaseUnitTest
```

O script baixa os componentes fixados do runtime e gera `app/build/outputs/apk/release/app-release.apk`. O caminho da chave pode ser configurado com `CODEX_MOBILE_KEYSTORE`. Para atualizar um APK instalado, use a mesma chave. Os nomes usados internamente no app e no protocolo independem da conta e da distribuição instaladas anteriormente no Termux.

As listas, folhas modais, animações e superfícies usam Compose e Material 3. Os ícones da interface são Lucide, com a licença incluída no APK. CommonMark interpreta a estrutura Markdown antes da renderização nativa. O backend reaproveita as operações do Codex App Server e responde apenas no loopback com um token efêmero.

## Ferramentas Android do Codex

O Ubuntu via PRoot é o ambiente padrão. Em conversas novas, o Codex também pode solicitar `termux_shell` ou `adb_shell` quando uma tarefa realmente exige aquele ambiente. Cada chamada mostra o comando e solicita autorização no chat.

- **Termux**. Em Configurações, toque em **Conectar Termux**. O Termux precisa autorizar comandos externos com `allow-external-apps=true` em `~/.termux/termux.properties`.
- **Shizuku**. Inicie o serviço com Depuração sem fio ou ADB e toque em **Conectar Shizuku**. A ferramenta `adb_shell` recebe diretamente a linha para o shell Android, sem o prefixo `adb shell`.

No Ubuntu, o mini shell mostra as últimas linhas enquanto o comando roda. Termux e Shizuku entregam o resultado ao fim da execução. Abra a linha do comando para examinar e copiar a saída registrada.

## Cofre de credenciais

Abra **Menu → Credenciais**, crie uma senha para o cofre e adicione um **Token**, um **Login** ou uma credencial **SSH**. Os nomes, descrições e dados ficam cifrados em armazenamento privado com AES-GCM e uma chave derivada da senha por PBKDF2-HMAC-SHA256.

Em uma conversa nova, peça ao Codex para usar a credencial pelo nome ou digite `+nome` no compositor. O autocomplete fica disponível com o cofre aberto. A primeira solicitação para cada credencial naquela conversa pede a senha. **Bloquear cofre** revoga as permissões em memória para novos comandos.

A ferramenta `credential_shell` entrega os dados ao comando do Ubuntu por variáveis de ambiente. O app mascara o segredo literal na saída capturada. O comando autorizado recebe o valor e pode usá-lo para autenticar, por isso confira a ação antes de autorizar.
