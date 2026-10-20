# Baixador para Android

App pessoal nativo em Kotlin para baixar fotos, vídeos e áudio. Inclui leitor de
posts e carrosséis do Instagram e usa
[yt-dlp](https://github.com/yt-dlp/yt-dlp) e
[youtubedl-android](https://github.com/yausername/youtubedl-android), com FFmpeg.
Processa e converte a mídia no celular; não depende de um servidor próprio.

## Instalar no telefone

Abra [Releases](https://github.com/ribau/Baixador/releases), baixe o arquivo
**Baixador-v1.1.0.apk**, abra-o e permita a instalação pelo navegador/gerenciador
de arquivos quando o Android solicitar. O APK universal serve para as arquiteturas
listadas abaixo. A versão 1.1.0 usa a mesma assinatura da 1.0.0: instale por cima
para preservar o histórico.

## Requisitos

- Android 10 ou superior.
- Internet e espaço livre. Durante a gravação final, é necessário espaço para o arquivo temporário e a cópia em Downloads.
- Arquiteturas ARM64, ARMv7 e x86_64.

## Como usar

1. Instale o APK e abra **Baixador**.
2. Cole um link e toque em **Consultar mídias, qualidade e tamanho**.
3. Marque as fotos ou vídeos desejados. Cada item tem prévia e sua própria seleção
   de qualidade, com tamanho ao lado. Vídeos com áudio também oferecem MP3 em 128/192/320 kbps.
4. Toque em **Baixar selecionadas**. Os itens são baixados um após o outro,
   inclusive com o app em segundo plano. Uma falha não impede os próximos itens.
5. O arquivo fica em **Downloads/Baixador**. Abra ou compartilhe pelo histórico.

Também é possível usar **Compartilhar → Baixador** no YouTube, Instagram ou outro app:
isso abre uma janela de download sobre o app atual. Texto selecionado pode ser enviado
ao Baixador pelo menu de processamento de texto, quando o app de origem oferece esse menu.

Para usar um link já copiado, adicione **Baixar link** aos ajustes rápidos do Android
(há um botão no app). Copie o link, abra os ajustes rápidos e toque no atalho.
A janela recebe foco e então consulta a área de transferência.

O botão **Atualizar motor de download** atualiza o yt-dlp pelos releases oficiais.
Use-o quando uma rede social passar a falhar. Essa operação exige internet.

### Instagram e carrosséis

O leitor do Instagram preserva a ordem e inclui fotos e vídeos do mesmo post.
Use **Selecionar todas** ou marque apenas os itens desejados.

O Instagram pode exigir uma sessão mesmo em páginas públicas que você não segue.
Quando isso ocorrer:

1. Toque em **Entrar no Instagram** (ou **Verificar sessão do Instagram**).
2. Faça login diretamente no site oficial dentro do app e conclua as confirmações solicitadas.
3. Use **Voltar e usar sessão** para consultar novamente.
4. Se a consulta ainda for bloqueada, abra essa tela novamente e toque em
   **Ler mídias deste post**. Essa alternativa consulta os dados pelo próprio navegador,
   usando a sessão ativa.

A sessão fica somente no armazenamento privado do Baixador. O app não tem servidor
de login e não captura sua senha. **Remover sessão do Instagram** apaga os cookies
e dados locais do site. Não há importação de sessões de outros aplicativos.
Login não remove bloqueios temporários, limites de acesso ou restrições do post.

### Qualidade e tamanho

- As opções correspondem aos formatos e resoluções disponibilizados pela fonte.
- O tamanho vem dos metadados ou de uma consulta ao arquivo. **≈** indica estimativa;
  quando não é possível obter o tamanho, aparece **tamanho não informado**.
- Para vídeo com áudio separado, o total inclui as duas faixas. A junção pode alterar
  um pouco o tamanho final.
- Para MP3, a estimativa representa o arquivo convertido. O tráfego de internet pode
  ser maior, pois o app precisa baixar a fonte antes de convertê-la.
- A seleção mostra o total conhecido; o histórico mostra o tamanho realmente salvo.

## Limites conhecidos

- **Não há popup automático global ao copiar.** Desde o Android 10, apps em segundo
  plano não podem ler a área de transferência pelas permissões normais. Permitir
  sobreposição de tela não elimina essa restrição. O app oferece compartilhamento,
  atalho rápido e sugestão opcional do link ao voltar à tela principal.
  [Referência oficial](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data).
- Suporte às redes depende dos [extratores do yt-dlp](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md).
  YouTube, Instagram, TikTok, Facebook, X etc. podem exigir login, limitar IPs
  ou mudar suas APIs. Não existe garantia de que qualquer link dessas redes funcione.
- A sessão integrada é exclusiva do Instagram. DRM, transmissões ao vivo, playlists,
  perfis e stories não são suportados. Carrosséis e posts com fotos são suportados no Instagram.
- MP3 em 320 kbps não melhora a qualidade de uma fonte inferior. Vídeos priorizam MP4,
  mas outros contêineres/codecs podem ser usados quando a fonte não oferece MP4.
- Um download por vez, com seleção de até 40 itens em sequência. Consultas e atualização
  do motor aguardam a seleção terminar. Se o Android encerrar o processo, o histórico
  registra a interrupção do item em andamento. Consulte o post e selecione novamente
  os itens restantes; a fila não é retomada automaticamente.
- O histórico guarda os últimos 40 downloads. Não há telemetria nem envio dos links
  a um backend próprio; as conexões são feitas com as redes e com os provedores do motor.
- Baixe conteúdo que você tem autorização para guardar.

## Compilar

Instale JDK 17 e Android SDK Platform 35 / Build Tools 35.0.0. Abra o projeto no
Android Studio ou configure `local.properties`:

```properties
sdk.dir=/caminho/para/Android/Sdk
```

Linux/macOS:

```sh
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

APK de desenvolvimento: `app/build/outputs/apk/debug/app-debug.apk`.
Ele é assinado com a chave de desenvolvimento local.

Para gerar uma release assinada no Windows:

```powershell
.\scripts\New-ReleaseKey.ps1  # somente uma vez
.\gradlew.bat assembleRelease
```

A chave fica em `.signing/baixador-release.jks` e a configuração em
`.signing/keystore.properties`; ambos são ignorados pelo Git. Faça backup privado
da pasta `.signing`: ela é necessária para instalar futuras versões sobre a atual.
Sem essa configuração, `assembleRelease` produz um APK não assinado.
APK assinado: `app/build/outputs/apk/release/app-release.apk`.
As releases publicadas manualmente usam essa chave, não a chave temporária do CI.

O workflow **Compilar APK Android** executa testes, lint e compilação em pull requests,
pushes em main/master e manualmente no GitHub Actions. Baixe o artefato **Baixador-APK**.
APKs de CI podem usar uma chave de debug diferente da compilação local.

## Validação em aparelho

Os testes unitários cobrem URLs, carrosséis mistos, preservação da ordem, formatos,
tamanhos e rejeição de mídias indisponíveis. Compilação e testes automatizados não
substituem uma verificação com a sessão e a rede do telefone. Antes de considerar
uma versão validada no seu aparelho, execute este roteiro:

- Compartilhar um vídeo público, consultar os formatos e baixar até 720p.
- Baixar o mesmo vídeo como MP3 e reproduzir o arquivo.
- Repetir com um reel público do Instagram e um vídeo de outra rede.
- Consultar um carrossel com fotos e vídeos, marcar itens não consecutivos e conferir a ordem e os arquivos.
- Conferir qualidade e tamanho de cada opção, total selecionado e tamanho salvo no histórico.
- Testar login, confirmações do Instagram, **Ler mídias deste post** e remoção da sessão.
- Copiar um link, tocar no atalho rápido e conferir o popup.
- Iniciar um download, voltar à tela inicial e acompanhar pela notificação.
- Cancelar durante o download e durante a conversão; confirmar ausência de arquivo incompleto em Downloads.
- Negar notificações, girar a tela, testar link inválido e desligar a rede durante uma operação.
- Abrir e compartilhar um arquivo pelo histórico.

O resultado depende também das restrições de bateria e da versão de Android do fabricante.

## Dependências

Veja [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). A biblioteca youtubedl-android
é distribuída sob GPL-3.0; este projeto usa a mesma licença, incluída em [LICENSE](LICENSE).
