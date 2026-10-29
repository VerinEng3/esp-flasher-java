# ESP Flasher — gravador de firmware para ESP32 e ESP8266

**ESP Flasher** é um aplicativo de desktop gratuito e de código aberto para gravar firmware em microcontroladores Espressif **ESP32**, **ESP32-S2/S3/C3/C6/H2** e **ESP8266**. Ele usa o [`esptool`](https://github.com/espressif/esptool) oficial por trás de uma interface gráfica, sem precisar de linha de comando.

Este repositório é o **fork em português da Verin**, voltado para gravação em produção no Windows. O projeto original é o [ESP Flasher de Ajinkya Gokhale](https://github.com/AjinkyaGokhale/esp-flasher-java).

![Janela principal do ESP Flasher](docs/screenshots/main.png)

---

## O que este fork acrescenta

- **Interface toda em português (pt-BR).**
- **Python e esptool embutidos no instalador.** O app traz um Python 3.13.13 portátil com o esptool 5.6.0, isolados do resto do computador: não dependem de nenhum Python da máquina e não alteram nada nela. É esse esptool que o app usa por padrão.
- **Painel de pré-requisitos na tela principal.** Mostra o caminho e a versão do Python e do esptool em uso.
- **Outro esptool, se precisar.** "Selecionar..." aponta um esptool manualmente, e Configurações > Caminhos lista os encontrados no PC (Python, PATH, ESP-IDF, PlatformIO, Arduino IDE, pipx) com um botão "Usar". "Restaurar padrão" volta ao embutido.
- **Lista de todas as instalações** em Configurações > Caminhos, com um botão "Usar" em cada uma.
- **Instalador para Windows com escolha de modo:** para todos os usuários ou somente para o usuário atual.
- **Atualização automática a partir deste fork.** Ao abrir, o app avisa quando há versão nova, instala sem perguntas e reabre sozinho.

---

## Funcionalidades

- Instalador para Windows com o Java embutido, sem precisar instalar Java.
- **Catálogo de firmwares populares:** grava Tasmota e Tasmota SML (ottelo9) com um clique, baixando o binário certo para o chip e guardando em cache.
- **Explorar projetos populares:** mostra os firmwares suportados com logo e link para o repositório.
- Modo **Binário próprio** para qualquer `.bin` (ESPHome, WLED, MicroPython, Arduino, ESP-IDF).
- Detecção automática da porta serial do ESP32/ESP8266 (CP210x, CH340, FTDI, USB nativo).
- Escolha do chip, velocidade (abre em 921600 baud, a mais rápida) e offset de gravação.
- Barra de progresso e saída do `esptool` em tempo real.
- **Modo Produção:** grava automaticamente cada placa assim que ela é conectada.
- **Arquivo de log** opcional em CSV com data, porta, endereço MAC e resultado de cada gravação.
- Tema claro ou escuro conforme o sistema.
- Contador de placas gravadas.
- Código aberto, licença MIT, sem telemetria.

---

## Instalação

1. Baixe o `ESP-Flasher-<versão>-setup.exe` na página de [Releases](https://github.com/VerinEng3/esp-flasher-java/releases/latest).
2. Execute o instalador e escolha o modo:
   - **Instalar para todos os usuários:** instala em `Arquivos de Programas` e pede administrador.
   - **Instalar somente para mim:** instala em `%LOCALAPPDATA%\Programs`, sem administrador.
3. Se o SmartScreen avisar, clique em **Mais informações** e depois em **Executar assim mesmo**. O instalador ainda não é assinado.

Ao instalar uma versão nova por cima, o instalador atualiza a existente e mantém o modo escolhido antes.

---

## Pré-requisitos

- **Python e esptool:** nenhum. Os dois já vêm no instalador.
- **Drivers USB:** placas com conversor CP210x ou CH340 podem precisar do driver do fabricante no Windows.

---

## Como gravar um ESP32 ou ESP8266

### Opção A — Firmware popular (download automático)

1. Em **Origem**, escolha um projeto do catálogo (ex.: **Tasmota** ou **Tasmota SML (ottelo9)**). A versão mais recente aparece ao lado.
2. Escolha o **Chip**. A lista mostra só os chips que o projeto suporta.
3. Escolha a **Porta** e a **Velocidade (baud)**.
4. Clique em **Gravar**. O app baixa o binário de fábrica certo, guarda em `~/.esp-flasher/firmware-cache` e grava. Trocar de chip depois reaproveita o cache até sair uma versão nova.

Dica: clique em **Explorar projetos populares →** para ver todos os firmwares suportados.

### Opção B — Binário próprio

1. Em **Origem**, escolha **Binário próprio**.
2. Clique em **Procurar...** e escolha o arquivo `.bin`.
3. Escolha o **Chip**, ou deixe em `auto` para o `esptool` detectar.
4. Escolha a **Porta**. Clique em **Atualizar** se a placa não aparecer.
5. A **Velocidade** já vem em `921600`, a mais rápida. Se a gravação falhar por conexão, use `460800` ou `115200`.
6. Defina o **Offset de gravação**: `0x0` para binários ESP32 unificados e para todo ESP8266, `0x1000` para só o bootloader do ESP32, `0x10000` para só a aplicação.
7. Clique em **Gravar**.

### Modo Produção — gravar várias placas em sequência

1. Escolha firmware, chip, velocidade e offset.
2. Clique em **Modo Produção** e confirme. O app fica esperando um dispositivo.
3. Conecte uma placa. A gravação começa sozinha.
4. Ao terminar (barra verde e som), desconecte e conecte a próxima.
5. Clique em **Parar Produção** quando acabar. O contador **gravados** mostra o total.

---

## Chips suportados

`auto` (detecção automática), **ESP32**, **ESP32-S2**, **ESP32-S3**, **ESP32-C3**, **ESP32-C6**, **ESP32-H2** e **ESP8266**.

Funciona com NodeMCU, Wemos D1 Mini, ESP32 DevKitC, ESP32-S3 DevKitC, ESP32-C3 SuperMini, M5Stack, Seeed XIAO ESP32, LilyGO T-Display, Adafruit Feather ESP32, Sonoff Basic/Mini e a maioria dos módulos Espressif genéricos.

---

## Compilar a partir do código

Requer JDK 21. O Maven vem embutido no projeto pelo `mvnw`.

```bash
# Rodar a partir do código
./mvnw clean package
java -jar target/espflasher-<versão>.jar
```

Para gerar o instalador do Windows é preciso ter o [Inno Setup 6](https://jrsoftware.org/isinfo.php) e o Python 3.13 64-bit (só para montar o Python embutido):

```bash
./mvnw clean package jpackage:jpackage
powershell -ExecutionPolicy Bypass -File installer/prepare-python.ps1
ISCC.exe /DAppVersion=<versão> installer/esp-flasher.iss
# Resultado: target/installer/ESP-Flasher-<versão>-setup.exe
```

### Publicar uma versão nova

1. Altere `<version>` no `pom.xml` (ex.: `1.3.0`).
2. Faça o commit e envie a tag: `git tag v1.3.0 && git push origin v1.3.0`.
3. O GitHub Actions gera o instalador e publica a release. Os computadores com o app instalado são avisados na próxima abertura.

### Versões do Python e do esptool embutidos

Ficam em `installer/prepare-python.ps1` (Python) e `installer/python/requirements.txt` (esptool e dependências, com hash de cada pacote). Para trocar de versão, altere esses dois arquivos e publique uma versão nova.

---

## Solução de problemas

| Sintoma | Solução |
| ------- | ------- |
| esptool sem "[Embutido]" ou "Não encontrado" | Clique em **Restaurar padrão** em Configurações > Caminhos. Se continuar, reinstale o ESP Flasher. |
| "[manual inválido]" ao lado do caminho | O caminho escolhido antes não existe mais. Use **Restaurar padrão** em Configurações > Caminhos. |
| Nenhuma porta aparece | Instale o driver USB-serial da placa (CP210x, CH340, FTDI) e clique em **Atualizar**. |
| "Timed out connecting to ESP" ou erro no meio da gravação | Segure o botão BOOT ao conectar a placa. Se o conversor USB-serial não aguentar `921600`, escolha `460800` ou `115200`. |
| Firmware não roda depois de gravar | Confira o offset. Muitas imagens ESP32 precisam de `0x1000` ou `0x10000`. |

A saída completa do `esptool` aparece na área de log. Inclua esse texto ao abrir um chamado.

---

## Contribuindo

Abra uma [issue](../../issues) antes de começar. Veja o [CONTRIBUTING.md](CONTRIBUTING.md) para configuração e convenções.

## Licença

[MIT](LICENSE) — © 2026 Ajinkya Gokhale. Adaptação para português e melhorias: Verin.

## Créditos

- [Ajinkya Gokhale](https://github.com/AjinkyaGokhale/esp-flasher-java) — projeto original
- [Espressif Systems](https://github.com/espressif/esptool) — `esptool`
- [Fazecast](https://github.com/Fazecast/jSerialComm) — `jSerialComm`
- [OpenJFX](https://openjfx.io) — JavaFX
- [Inno Setup](https://jrsoftware.org/isinfo.php) — instalador do Windows
