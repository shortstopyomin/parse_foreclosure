`parser.jar` is a checked-in build artifact compiled from `../src/Main.kt`. It is not rebuilt automatically — there is no CI/build-step compilation for this trial (see `openspec/changes/replace-land-building-parser-with-kotlin/design.md`, decision on jar build strategy).

Rebuild after any change to `src/Main.kt`:

```
kotlinc src/Main.kt -include-runtime \
  -classpath "libs/pdfbox-2.0.31.jar:libs/fontbox-2.0.31.jar:libs/commons-logging-1.2.jar" \
  -d dist/parser.jar
```

Then commit the updated `dist/parser.jar`. `api-core` invokes it as:

```
java -cp "dist/parser.jar:libs/pdfbox-2.0.31.jar:libs/fontbox-2.0.31.jar:libs/commons-logging-1.2.jar" MainKt --json <pdf-path>
```
