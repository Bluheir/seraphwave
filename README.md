# Seraphwave
Seraphwave is a proximity voice chat plugin for Spigot. Other plugins require mods as the Minecraft clients do not support voice chat. This plugin uses a web server to transmit and receive audio.

## How to use
To use this plugin, first add the plugin jar file to the plugins folder for your Spigot server. When the server is run, a web server will run in the background. Players can access this web server at `https://(server IP address):65437/create` (the port will be different depending on configuration options). Players can then execute the command `/joinprox (code)` to join the voice chat.

## Permissions
- `seraphwave.joinprox` - permission to use the `/joinprox` command.

### Config
See [the config file](./seraph-wave/src/main/resources/config.yml) for the default configuration and meanings for each configuration option.

### Note for server admins
Seraphwave generates a self-signed certificate for HTTPS. When players access the web server, they will receive a scary warning that the certificate is self-signed. If you do not want this warning to appear, please serve the web server behind a proxy, the proxy using a certificate signed by a trusted certificate authority. Otherwise, tell players to proceed to the website regardless of the warning.

## Project structure
The code for the Spigot plugin is located in [./seraph-wave](./seraph-wave). The code for the web client GUI is located in [./seraph-wave-gui](./seraph-wave-gui/).

## Questions and answers

### Why should I use this proximity chat plugin and not alternatives?
There are a number of alternatives to this plugin, all with their own disadvantages:

- Skoice, using Discord for its proximity chat, which does not allow for directional audio;
- SimpleVoiceChat, requiring a mod to function; and
- OpenAudioMC, not fully self-hosted and additionally under a strange license.

An advantage this plugin has over all the alternatives is that it uses Scala as its programming language.

### Why use HTTPS instead of HTTP by default?
HTTPS is used by default because browsers do not allow microphone access without a secure connection.

## Plugin compilation
The required dependencies for compilation are [SBT](https://www.scala-sbt.org/download) and [Bun](https://bun.sh).

### for Linux/Unix systems
```sh
git clone https://github.com/Bluheir/seraphwave.git
cd ./seraphwave/seraph-wave-gui
bun install
bun run build
cd ..
./mill seraph-wave.assembly
```

### for Windows systems
```bat
git clone https://github.com/Bluheir/seraphwave.git
cd .\seraphwave\seraph-wave-gui
bun install
bun run build
cd ..\
.\mill.bat seraph-wave.assembly
```

The resultant plugin jar file will be located at `./out/seraph-wave/assembly.dest/Seraphwave-(version).jar`.

## License
This project is licensed under the terms of the Apache-2.0 license. Refer to [LICENSE.md](./LICENSE.md).