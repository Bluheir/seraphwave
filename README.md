# Seraphwave
Seraphwave is a proximity voice chat plugin for Spigot. Other plugins require mods as the Minecraft clients do not support voice chat. This plugin uses a web GUI hosted on an HTTP server to transmit and receive audio.

## Project structure
The code for the Spigot plugin is located in [./seraph-wave](./seraph-wave). The code for the web client GUI is located in [./seraph-wave-gui](./seraph-wave-gui/).

## Questions and answers

### Why should I use this proximity chat plugin and not alternatives?
There are a number of alternatives to this plugin, all with their own disadvantages:

- Skoice, using Discord for its proximity chat, which does not allow for directional audio;
- SimpleVoiceChat, requiring a mod to function; and
- OpenAudioMC, not fully self-hosted and additionally under a strange license.

An advantage this plugin has over all the alternatives is that it uses Scala as its programming language.

## Plugin compilation
The required dependencies for compilation are [Gradle](https://gradle.org/install) and [Bun](https://bun.sh).

To compile this plugin, `git clone` this repository, then execute the following commands in the terminal.

### for Linux/Unix systems
```sh
# compiling GUI
cd ./seraph-wave/seraph-wave-gui
bun run build
# compiling plugin
cd ../seraph-wave
gradle build
```

### for Windows systems
```bat
:: compiling GUI
cd .\seraph-wave\seraph-wave-gui
bun run build
:: compiling plugin
cd ..\seraph-wave
gradle build
```

The resultant plugin jar file will be located in `./seraph-wave/build/libs`.