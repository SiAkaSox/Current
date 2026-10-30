# Vendored: displee rs-cache-library

`com.displee.*` is a source copy of the displee cache library as forked by OpenRune FileStore 3.0.0
(`dev.or2:displee`, `exampleproject/openrune/displee`), which carries internal fixes over the
`com.displee:rs-cache-library` artifact on Maven Central. It is copied unmodified.

It is the read/write layer of kronos-cachetools: archive put/remove, XTEA, GZIP/BZIP2 containers,
reference table and idx255 updates, and `rebuild` (compacting sector copy).

Runtime dependencies (declared in `kronos-cachetools/build.gradle`): `com.displee:disio`,
`com.github.jponge:lzma-java`, `org.apache.ant:ant` (bzip2).
