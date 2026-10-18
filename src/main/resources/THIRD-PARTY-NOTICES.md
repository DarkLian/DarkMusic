# DarkMusic bundled libraries

DarkMusic's own sources retain the project's CC0-1.0 license. Third-party
libraries retain their original copyright notices and licenses. They are
included as separate, unmodified nested JARs under META-INF/jars.

| Maven coordinate                                | Applicable source notices                                                                      |
|-------------------------------------------------|------------------------------------------------------------------------------------------------|
| com.googlecode.soundlibs:mp3spi:1.9.5.4         | JavaZOOM; GNU Library General Public License v2 or later                                       |
| com.googlecode.soundlibs:vorbisspi:1.0.3.3      | JavaZOOM; GNU Library General Public License v2 or later                                       |
| com.googlecode.soundlibs:jlayer:1.0.1.4         | JavaZOOM and original contributors; LGPL-family notices in sources                             |
| com.googlecode.soundlibs:tritonus-share:0.3.7.4 | Matthias Pfisterer and Tritonus contributors; GNU Library General Public License v2 or later   |
| org.jflac:jflac-codec:1.5.2                     | Josh Coalson and jFLAC contributors; Library GPL v2-or-later and Apache-2.0 file-level notices |
| net.jthink:jaudiotagger:3.0.1                   | Paul Taylor, Raphael Slinckx and contributors; LGPL-2.1-or-later                               |

Complete LGPL-2.0, LGPL-2.1 and Apache-2.0 texts are in licenses/. Original
per-file copyright and license notices are preserved in the corresponding
source archives in darkmusic-2.0.0-third-party-sources.zip, built alongside
the mod by `gradlew build`. Distribute that source bundle with the mod.

Corresponding sources are also obtainable from Maven Central using the exact
coordinates above and the `sources` classifier. Repository base:
https://repo.maven.apache.org/maven2/

The nested JARs are not relocated or obfuscated. To replace a library, change
its version in build.gradle and rebuild using the documented Java 25/Gradle
toolchain; DarkMusic source and build scripts accompany the project. Do not
remove notices or restrict modification/reverse engineering needed to debug
changes to LGPL-covered libraries.

Minecraft provides JOrbis 0.0.17. DarkMusic does not redistribute a second
JOrbis copy. Fabric API is a separately installed dependency (Apache-2.0).
Gson and SLF4J are provided by the Minecraft/Fabric runtime.

yt-dlp, FFmpeg, FFprobe, and JavaScript runtimes are external tools for
downloading/conversion. They are not bundled with the mod or required for local
playback. Users can explicitly install/update a verified instance-local tool
bundle or configure existing executables. The managed bundle records publisher
sources and releases in its manifest and retains the published FFmpeg license
and README. Tool releases retain their own licenses; installing them does not
change the licenses of DarkMusic or its bundled audio libraries. Audio tests read a private music
collection and temporary excerpts/conversions derived from it. Music and its
artwork retain their original rights and are never bundled or published.
