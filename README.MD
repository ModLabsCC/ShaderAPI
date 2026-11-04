# ShaderAPI

A client-side Fabric mod that lets servers offer shader packs for download and apply them with Iris.

## Requirements

This branch targets **Minecraft 26.3** and **Java 25**. Install matching builds of:

- [Fabric Loader](https://fabricmc.net/use/installer/) 0.20.1 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.3
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) 1.14.2+kotlin.2.4.21 or newer
- [Iris Shaders](https://modrinth.com/mod/iris/version/vTN4NRGW) 1.11.7 for Fabric 26.3, together with its required [Sodium](https://modrinth.com/mod/sodium/version/bAZQdGpg) 0.9.2 for 26.3

ShaderAPI disables its client integration when Iris is missing.

## Usage

Connect to a server implementing the ShaderAPI protocol. The client requests the server's shader pack and asks for approval before downloading and applying it. Acceptance is remembered by server group.

Offers must provide a 40-digit SHA-1 hash; downloaded and cached packs are checked against it. Rejection is also remembered by server group. To change a saved decision, remove that group's entry from `config/shaderapi.json` while the game is closed.

A server-side implementation for Paper is available in [ShaderAPI-Paper](https://github.com/Liams-Services/ShaderAPI-Paper). Client support for 26.3 does not establish compatibility of that separate server plugin.

## Building

Install JDK 25 and point `JAVA_HOME` at it, then run the included Gradle wrapper:

```sh
./gradlew clean build
```

On Windows, use `gradlew.bat clean build`. No separate Gradle installation is needed. The build targets Minecraft 26.3 only; it no longer has a `chiseledBuild` task. The mod JAR and sources JAR are written to `build/libs/`.

For an interactive development client, put the matching Iris and Sodium JARs in `run/mods/`, then run:

```sh
./gradlew runClient
```

Before release, verify the approval/denial prompt, downloading and applying a valid pack, a cached pack, failed downloads, and shader reloads in a real client. A successful build does not verify GPU rendering or server-plugin compatibility.
