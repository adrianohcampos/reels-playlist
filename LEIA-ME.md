# Reels Playlist

Salve Reels do Instagram pelo Chrome (ou pelo celular) e assista em sequência na TV.
Custo: **R$ 0**. Firebase no plano gratuito guarda só os links, o GitHub compila o app de graça
e a TV baixa os vídeos sozinha pela internet de casa.

```
extensao-chrome/   → extensão do Chrome (salvar Reels no desktop)
firebase/          → regras de segurança do banco
app-tv/            → app Android TV (Fire Stick e TV Philips)
.github/workflows/ → compila o app da TV automaticamente no GitHub
```

---

## Parte 1: Firebase + extensão (você já fez ✅)

Se ainda não fez:
1. Em <https://console.firebase.google.com>, crie um projeto e depois um **Firestore Database**
   (local `southamerica-east1`, modo produção).
2. Na aba **Regras**, cole o conteúdo de `firebase/firestore.rules` e clique em **Publicar**.
3. No Chrome, abra `chrome://extensions`, ative o **Modo do desenvolvedor**, clique em
   **Carregar sem compactação** e escolha a pasta `extensao-chrome`.
4. Nas configurações da extensão, preencha o Project ID e a Web API key, clique em **Gerar código**
   e depois em **Testar conexão**.

---

## Parte 2: app da TV

### 2.1 Atualize as regras e a extensão (2 min)
- **Regras:** cole a nova versão de `firebase/firestore.rules` no Firebase e clique em **Publicar**.
  Ela inclui o pareamento da TV.
- **Extensão:** substitua os arquivos da pasta `extensao-chrome` pelos novos e, em
  `chrome://extensions`, clique em ⟳ (recarregar) no card da Reels Playlist.
  Nas configurações vai aparecer a seção **"📺 Conectar uma TV"**.

### 2.2 Compile o APK de graça no GitHub (≈10 min, só uma vez)
1. Crie uma conta em <https://github.com> (grátis), se ainda não tiver.
2. Clique em **New repository**, use o nome `reels-playlist` e marque **Public**. Depois clique em **Create repository**.
   > Pode ser público. Não há nada secreto no código: o Project ID e a API key do Firebase
   > são públicos por natureza, e o que protege a sua playlist é o código secreto, que **não** vai pro GitHub.
3. Clique em **uploading an existing file** e arraste **todo o conteúdo** desta pasta.
   Depois clique em **Commit changes**.
   - ⚠️ A pasta `.github` costuma ficar oculta no Windows e no Mac. Se ela não subir, vá em
     **Add file → Create new file**, digite o nome `.github/workflows/build-tv.yml`,
     cole o conteúdo do arquivo e clique em **Commit**.
4. Abra **Settings → Secrets and variables → Actions**, vá na aba **Variables** e clique em **New repository variable**. Crie duas:
   - `FIREBASE_PROJECT_ID` = o seu Project ID
   - `FIREBASE_API_KEY` = a sua Web API key
5. Vá na aba **Actions**, escolha **Compilar app da TV** e clique em **Run workflow**. Leva uns 5 a 8 minutos.
6. Quando terminar (✅ verde), o APK fica neste link fixo:
   ```
   https://github.com/SEU_USUARIO/reels-playlist/releases/latest/download/reels-tv.apk
   ```
   Se der ❌ vermelho, abra o erro, copie as últimas linhas do log e me mande.

### 2.3 Instale no Fire Stick
1. **Ative as Opções do desenvolvedor:** vá em Configurações → Minha Fire TV → Sobre e clique 7 vezes
   no nome do aparelho.
2. Na loja da Amazon, instale o app **Downloader** (ícone laranja).
3. Vá em Configurações → Minha Fire TV → Opções do desenvolvedor → **Instalar apps desconhecidos**
   e ative para o **Downloader**.
4. Abra o Downloader, digite o link do APK do passo 2.6 e clique em **Instalar**.
   > Dica: pra digitar menos no controle, encurte o link no computador (ex.: is.gd) e digite o link curto.

### 2.4 Instale na TV Philips (Android TV)
Instale o **Downloader** pela Play Store da TV e abra com o mesmo link. Quando a TV pedir,
permita "instalar apps desconhecidos" para o Downloader.

### 2.5 Samsung
O sistema da Samsung (Tizen) não consegue rodar o yt-dlp. **Ligue o Fire Stick nela** e use o app por ali.

### 2.6 Conecte a TV à playlist
1. Abra o **Reels TV** na TV. Aparece um código, por exemplo `K7PQ-2MXA`.
2. No Chrome, clique no ícone da extensão, vá em ⚙️ → **Conectar uma TV**, digite o código e clique em **Conectar**.
3. Em poucos segundos a TV mostra a sua playlist e começa a baixar os vídeos.

> Na primeira abertura, o app atualiza o yt-dlp. Isso pode levar uns 30 segundos.

---

## Usando na TV

| Tecla | Na tela da playlist | Assistindo |
|---|---|---|
| Setas | navegar | ◀ anterior · ▶ próximo |
| OK | tocar a partir daquele Reel | pausar / continuar |
| **☰ (menu)** ou segurar OK | opções do Reel: visto/não visto, baixar de novo, abrir pelo Instagram, remover | — |
| Voltar | sair | voltar para a lista |

- **▶ Assistir novos** toca só o que você ainda não viu, na ordem em que salvou.
- **▶ Assistir tudo** toca a playlist inteira.
- Cada vídeo que termina (ou que você pula) fica marcado como **visto** em todos os aparelhos.
- A TV mantém até 20 vídeos novos baixados e apaga sozinha os já vistos quando passa de 40.
- A lista atualiza sozinha a cada minuto. Pra forçar, use o botão **Atualizar**, que também tenta de novo os que deram erro.

## Se aparecer "⚠ erro" num Reel
- Às vezes o Instagram bloqueia downloads sem login, ou o Reel é de conta privada ou foi apagado.
  Abra o ☰ do item e escolha:
  - **Baixar agora / tentar de novo**, ou
  - **Abrir pelo player do Instagram (embed)**, o plano B, que toca direto do Instagram.
- Se **todos** derem erro, provavelmente o Instagram mudou algo. Feche e abra o app pra ele
  atualizar o yt-dlp. Se continuar, me avise.

## Como os dados ficam guardados
```
playlists/{codigoSecreto}/items/{shortcode}   url, shortcode, addedAt, watched, title, source
pairing/{PIN}                                 temporário, apagado assim que a TV conecta
```

---

## Parte 3: app do celular (Android)

Um app web instalável, hospedado de graça no **GitHub Pages**, no mesmo repositório.
Depois de instalado, ele aparece no **Compartilhar** do Instagram.

### 3.1 Suba os arquivos novos pro GitHub (5 min)
No seu repositório `reels-playlist`:
1. Clique em **Add file → Upload files**, arraste a pasta **`pwa`** inteira e também a pasta
   **`extensao-chrome`** (que foi atualizada). Depois clique em **Commit changes**.
2. Crie o workflow de publicação: **Add file → Create new file** com o nome
   `.github/workflows/pages.yml`. Cole o conteúdo do arquivo e clique em **Commit**.
   (Se você conseguiu arrastar a pasta `.github` inteira, ele já subiu junto.)
3. Abra **Settings → Pages** e, em *Build and deployment → Source*, escolha **GitHub Actions**.
4. Vá na aba **Actions**, escolha **Publicar app do celular** e clique em **Run workflow** (se ele não rodou sozinho).
   Em uns 2 minutos o app fica no ar em:
   ```
   https://SEU_USUARIO.github.io/reels-playlist/
   ```
   > O `pwa/config.js` do repositório está vazio de propósito. Na publicação, ele é preenchido
   > com as mesmas variáveis `FIREBASE_PROJECT_ID` e `FIREBASE_API_KEY` que você já criou.

Também atualize a extensão no Chrome: em `chrome://extensions`, clique em ⟳ no card dela.

### 3.2 Instale no celular
1. No **Chrome do Android**, abra o link acima.
2. Toque no menu **⋮** e escolha **Instalar app** (ou **Adicionar à tela inicial → Instalar**).
   ⚠️ Precisa ser *instalado*. Só assim ele aparece no menu "Compartilhar".
3. Abra o **Reels Playlist** pela tela inicial. Ele mostra um código de 8 caracteres.
4. No computador, vá na extensão → ⚙️ → **Conectar uma TV ou celular**, digite o código e clique em **Conectar**.

### 3.3 Usando
- **No Instagram:** toque no ✈️ (Compartilhar) do Reel, escolha **Compartilhar em…** ou **Mais** e depois **Reels Playlist**.
  O app abre, mostra "✓ Salvo na playlist" e tenta fechar sozinho. Se não fechar, é só voltar.
  > Dica: na lista de compartilhamento do Android, segure o ícone do Reels Playlist e **fixe** ele no topo.
- **Link copiado:** abra o app e toque em **📋 Colar**. Se for um link de Reel, ele já salva.
- O app mostra os últimos 20 salvos (✔ visto / novo) e deixa remover.
- ⚙️ → **Desconectar** tira o celular da playlist sem apagar nada.
