# Enum names are stored (saved state, Pronote caches) and passed in intents
# (widgets, shortcuts, tile): R8 must not rename them.
-keepclassmembers enum fr.sallio.** { *; }

# Open source anyway: readable names and line numbers make a crash report
# (Analytics) point at the real code without keeping a mapping per release.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
