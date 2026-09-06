# IPTV Caseiro

Aplicação Flask local para organizar e reproduzir vídeos próprios ou streams autorizados. A primeira versão inclui catálogo responsivo, player MP4/HLS, administração de canais, SQLite e playlist M3U dinâmica.

## Requisitos

- Windows 10 ou 11
- Python 3.10 ou superior
- FFmpeg opcional (a aplicação funciona sem ele nesta versão)

Se `python --version` abrir a Microsoft Store ou falhar, instale o Python pelo [site oficial](https://www.python.org/downloads/windows/) e marque **Add Python to PATH** durante a instalação. Feche e reabra o terminal depois.

## Executar no VS Code

Abra esta pasta no VS Code e, no terminal integrado, execute:

```powershell
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

Abra `http://localhost:5000` no navegador.

## Testar um vídeo local

O projeto já inclui `videos/video-teste.mp4`, um pequeno vídeo em domínio público (CC0) usado nos exemplos da MDN. Para testar:

1. Inicie o sistema com `python app.py`.
2. Abra o catálogo, selecione **Canal de Teste** e clique em **Assistir**.
3. Use **Voltar aos canais** para retornar à lista.

Durante a reprodução, use os controles **Canal** para avançar ou voltar entre os canais ativos sem retornar ao catálogo. Os botões de volume ajustam o player em passos de 10%, oferecem modo mudo e preservam o nível escolhido ao trocar de canal.

O tipo **Link externo** abre uma página oficial em nova aba e não tenta reproduzi-la como vídeo. Autenticações, como o login do Globoplay, são feitas exclusivamente no site de destino; o IPTV Caseiro não solicita nem armazena essas credenciais. Links externos não entram na playlist M3U nem nos controles de troca de canal.

Você pode substituir esse arquivo por um MP4 próprio mantendo o mesmo nome.

Também é possível cadastrar outros arquivos em **Administrar**. Informe somente o nome do arquivo, por exemplo `ferias.mp4`; ele precisa estar diretamente dentro de `videos`.

## Acessar pelo celular ou Smart TV

O terminal mostra o endereço da rede ao iniciar, por exemplo `http://192.168.1.20:5000`. Conecte o outro aparelho ao mesmo Wi-Fi e abra esse endereço no navegador.

Se não conectar, permita o Python em **Firewall do Windows > Permitir um aplicativo pelo firewall**, apenas para redes privadas. Redes com isolamento de clientes podem impedir que os aparelhos se enxerguem.

## Streams e HLS

Cadastre uma URL HTTP/HTTPS que você tenha autorização para usar e escolha o tipo **Stream**. URLs `.m3u8` usam o suporte nativo do navegador ou hls.js. Alguns servidores externos bloqueiam reprodução por CORS; isso depende da configuração da origem.

A playlist dos canais ativos fica disponível em `http://localhost:5000/playlist.m3u`.

### Importar uma playlist M3U

Em **Administrar > Importar M3U**, informe uma playlist pública, visualize os canais encontrados e marque apenas os que deseja cadastrar. A lista brasileira do IPTV-org já aparece preenchida como sugestão. O sistema limita o download a 2 MB, mostra no máximo 500 canais, ignora fontes duplicadas e bloqueia URLs de rede local na importação remota.

Uma cópia local da lista Brasil fica em `playlists/br.m3u` e é usada automaticamente quando o servidor não consegue acessar a internet. Para outras URLs, o acesso online continua necessário.

Na lista de administração, marque canais individualmente ou use **Selecionar todos** e depois **Excluir** para remover vários registros de uma vez. A exclusão não apaga arquivos da pasta `videos`.

## Estrutura

```text
app.py                 Aplicação, rotas, validação e banco SQLite
database/banco.db      Criado automaticamente na primeira execução
templates/             Páginas HTML
static/css/style.css   Interface responsiva
static/js/             Catálogo, administração e player HLS
videos/                Arquivos locais autorizados
utils/streaming.py     Verificação do FFmpeg para uso futuro
```

## Segurança e limites desta versão

Arquivos locais são servidos apenas da pasta `videos`, com nomes e extensões validados. URLs aceitam somente HTTP/HTTPS e todas as consultas SQLite usam parâmetros. A administração ainda não possui login, portanto use o servidor somente em uma rede local confiável. Não exponha a porta 5000 à internet.

FFmpeg não é necessário para MP4 compatível com o navegador. Futuras funções de conversão poderão usá-lo, mas nenhum comando fornecido pelo navegador é executado no sistema.

## Aplicativo Android 2.0 autônomo

O diretório `android/` agora contém um aplicativo nativo em Kotlin e Jetpack Compose. Ele não usa WebView, Flask nem o computador: os canais ficam no próprio aparelho em um banco Room e são reproduzidos pelo Media3 ExoPlayer.

O catálogo começa vazio. Em **Gerenciar**, é possível cadastrar streams HTTP/HLS, links externos, escolher vídeos com o seletor seguro do Android, importar playlists M3U por URL ou arquivo, editar, favoritar, ativar e excluir vários itens. URLs privadas ficam armazenadas somente na área interna do aplicativo e aparecem ocultadas na interface. A importação aceita até 25 MB e 10.000 canais por playlist.

O aplicativo também oferece pesquisa, categorias, layout adaptável para celular/tablet, launcher e navegação por controle remoto na Android TV, além de backup AES-256-GCM protegido por senha. A reprodução é pausada quando o aplicativo sai do primeiro plano.

O botão **Atualizar** consulta a última GitHub Release e sempre apresenta o resultado. Também há uma verificação silenciosa ao abrir, limitada a uma vez por dia. O pacote `br.com.iptvcaseiro` e a chave de assinatura permanecem os mesmos para permitir atualização sobre a versão 1.0.1.

### Aplicativo Android legado 1.x e página de instalação

O diretório `android/` contém um aplicativo Android que exibe este sistema em uma WebView. No primeiro uso, ele pede o endereço mostrado pelo Flask, por exemplo `http://192.168.1.20:5000`. O computador e o Android precisam estar na mesma rede, exceto quando o Flask estiver hospedado em um servidor HTTPS próprio.

O aplicativo possui os botões **Servidor** e **Atualizar**. A atualização consulta a última Release deste repositório, baixa o arquivo `iptv-caseiro.apk` e abre a confirmação do Android. Por segurança, o Android não permite que um aplicativo comum conclua a instalação silenciosamente.

A página pública de download está em `docs/`. Os workflows em `.github/workflows/` publicam essa página e geram o APK assinado.

### 1. Criar a chave de assinatura (somente uma vez)

Antes da primeira versão pública, confirme se o identificador `br.com.iptvcaseiro` em `android/app/build.gradle.kts` será o definitivo. Ele precisa ser único e não deve mudar depois que o aplicativo for instalado. Para distribuição no Brasil, também prepare o cadastro desse identificador e do certificado de assinatura no [Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console?hl=pt-br). As novas proteções de verificação começam em 30 de setembro de 2026 em aparelhos Android certificados no Brasil.

Instale o Android Studio/JDK e execute, na raiz do projeto:

```powershell
keytool -genkeypair -v -keystore android/release.keystore -alias iptv-caseiro -keyalg RSA -keysize 2048 -validity 10000
```

Guarde a chave e as senhas em um local seguro. Se a chave for perdida, os aparelhos que já possuem o aplicativo não aceitarão as futuras atualizações.

Converta a chave para Base64 no PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("android/release.keystore")) | Set-Clipboard
```

No GitHub, abra **Settings > Secrets and variables > Actions** e cadastre:

- `ANDROID_KEYSTORE_BASE64`: conteúdo Base64 copiado;
- `ANDROID_KEYSTORE_PASSWORD`: senha da chave;
- `ANDROID_KEY_ALIAS`: `iptv-caseiro`;
- `ANDROID_KEY_PASSWORD`: senha do alias.

O arquivo `release.keystore` é ignorado pelo Git e nunca deve ser publicado.

### 2. Publicar no GitHub

Crie um repositório **público** e envie este projeto para a branch `main`. O repositório público é necessário porque o aplicativo consulta a API pública de Releases sem armazenar credenciais. Depois, em **Settings > Pages > Build and deployment**, selecione **GitHub Actions**. O workflow **Publicar página Android** mostrará o endereço final, normalmente:

```text
https://SEU-USUARIO.github.io/NOME-DO-REPOSITORIO/
```

### 3. Criar a primeira versão

Crie e envie uma tag no formato `vMAJOR.MINOR.PATCH`:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

O workflow **Gerar APK e Release** compilará `iptv-caseiro.apk` com o nome correto do repositório e o anexará à Release. A página de instalação passará a encontrá-lo automaticamente.

Para cada atualização, altere o código, envie os commits e crie uma tag maior, como `v1.0.1`. Não reutilize uma versão antiga. O botão **Atualizar** compara a versão instalada com a Release mais recente.

### Permissões Android

- Internet e estado da rede: acesso ao servidor Flask e ao GitHub;
- instalação de pacotes: usada somente após o usuário escolher baixar uma atualização;
- tráfego HTTP local: necessário para endereços como `http://192.168.x.x:5000`.

O aplicativo não solicita acesso geral aos arquivos, contatos, câmera, microfone ou localização.
